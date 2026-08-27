package org.osm2world.buildingtiler.application;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.gis.DatasetErrorCode;
import org.osm2world.buildingtiler.gis.DatasetImportException;
import org.osm2world.buildingtiler.modeling.RepresentativeSampleSelector;
import org.osm2world.buildingtiler.tiles.ModelPreviewWriterAdapter;

public final class ModelPreviewService {

	public static final String DISCLAIMER = "屋顶、窗户与材质为程序化简模效果，不代表真实建筑测绘结果。";
	private final Path storageRoot;
	private final Duration ttl;
	private final DatasetService datasets;
	private final RepresentativeSampleSelector selector;
	private final ModelPreviewWriterAdapter writer;
	private final Map<UUID, ManagedModelPreview> previews = new ConcurrentHashMap<>();

	public ModelPreviewService(Path storageRoot, Duration ttl, DatasetService datasets,
			RepresentativeSampleSelector selector, ModelPreviewWriterAdapter writer) {
		this.storageRoot = storageRoot.toAbsolutePath().normalize();
		this.ttl = ttl == null ? Duration.ofHours(2) : ttl;
		if (this.ttl.isZero() || this.ttl.isNegative()) throw new IllegalArgumentException("Preview TTL must be positive");
		this.datasets = datasets;
		this.selector = selector;
		this.writer = writer;
	}

	public ManagedModelPreview create(String datasetId, HeightMapping heightMapping,
			ModelingConfig config) throws DatasetImportException {
		if (heightMapping == null || config == null) {
			throw new DatasetImportException(DatasetErrorCode.INVALID_REQUEST,
					"Height mapping and modeling config are required");
		}
		ensureStorageRoot();
		var dataset = datasets.materialize(datasetId, heightMapping);
		var selection = selector.select(dataset.buildings(), config);
		if (selection.features().isEmpty()) {
			throw new DatasetImportException(DatasetErrorCode.MODEL_PREVIEW_GENERATION_FAILED,
					"Dataset contains no building eligible for model preview");
		}
		UUID id = UUID.randomUUID();
		Instant created = Instant.now();
		Path output = storageRoot.resolve("preview-" + id).normalize();
		if (!output.startsWith(storageRoot)) throw new IllegalStateException("Preview path escaped storage root");
		ManagedModelPreview preview = new ManagedModelPreview(id, datasetId, created, created.plus(ttl),
				output, heightMapping, config);
		previews.put(id, preview);
		try {
			var result = writer.write(selection.features(), config, output,
					selection.bucketCoverage(), selection.selectionHash());
			preview.ready(result, selection.bucketCoverage());
			return preview;
		} catch (Exception exception) {
			preview.status(ModelPreviewStatus.FAILED);
			previews.remove(id, preview);
			try { deleteTree(output); }
			catch (IOException cleanupFailure) { exception.addSuppressed(cleanupFailure); }
			throw new DatasetImportException(DatasetErrorCode.MODEL_PREVIEW_GENERATION_FAILED,
					"Model preview generation failed: " + exception.getMessage(), exception);
		}
	}

	public ManagedModelPreview get(String id) throws DatasetImportException {
		UUID uuid = parseId(id);
		ManagedModelPreview preview = previews.get(uuid);
		if (preview == null || preview.status() != ModelPreviewStatus.READY) throw notFound(id);
		preview.touch();
		return preview;
	}

	public Path resultFile(String id, String fileName) throws DatasetImportException {
		ManagedModelPreview preview = get(id);
		if (fileName == null || !fileName.matches("(?:tileset\\.json|tileset\\.glb|preview-report\\.json)")) {
			throw new DatasetImportException(DatasetErrorCode.MODEL_PREVIEW_NOT_FOUND,
					"Preview result file does not exist: " + fileName);
		}
		Path file = preview.outputDirectory().resolve(fileName).normalize();
		if (!file.startsWith(preview.outputDirectory()) || !Files.isRegularFile(file)) {
			throw new DatasetImportException(DatasetErrorCode.MODEL_PREVIEW_NOT_FOUND,
					"Preview result file does not exist: " + fileName);
		}
		return file;
	}

	public boolean delete(String id) throws IOException {
		UUID uuid;
		try { uuid = UUID.fromString(id); }
		catch (RuntimeException exception) { return false; }
		ManagedModelPreview preview = previews.remove(uuid);
		if (preview == null) return false;
		preview.status(ModelPreviewStatus.DELETING);
		deleteTree(preview.outputDirectory());
		preview.status(ModelPreviewStatus.DELETED);
		return true;
	}

	public int cleanupExpired(Instant now) {
		int removed = 0;
		for (ManagedModelPreview preview : previews.values()) {
			if (preview.expiresAt().isBefore(now) && previews.remove(preview.id(), preview)) {
				try {
					preview.status(ModelPreviewStatus.DELETING);
					deleteTree(preview.outputDirectory());
					preview.status(ModelPreviewStatus.DELETED);
					removed++;
				} catch (IOException ignored) {
					// Orphan cleanup retries on the next scheduled pass.
				}
			}
		}
		if (Files.isDirectory(storageRoot)) {
			try (var paths = Files.list(storageRoot)) {
				for (Path path : paths.filter(Files::isDirectory)
						.filter(value -> value.getFileName().toString().startsWith("preview-")).toList()) {
					boolean live = previews.values().stream().anyMatch(value -> value.outputDirectory().equals(path));
					if (!live && Files.getLastModifiedTime(path).toInstant().plus(ttl).isBefore(now)) {
						deleteTree(path);
						removed++;
					}
				}
			} catch (IOException ignored) {
				// Cleanup is best effort.
			}
		}
		return removed;
	}

	public int size() { return previews.size(); }

	private void ensureStorageRoot() throws DatasetImportException {
		try {
			Files.createDirectories(storageRoot);
			if (!Files.isDirectory(storageRoot) || !Files.isWritable(storageRoot)) {
				throw new IOException("Preview storage root is not writable");
			}
		} catch (IOException exception) {
			throw new DatasetImportException(DatasetErrorCode.STORAGE_UNAVAILABLE,
					"Model preview storage is unavailable: " + exception.getMessage(), exception);
		}
	}

	private static UUID parseId(String id) throws DatasetImportException {
		try { return UUID.fromString(id); }
		catch (RuntimeException exception) { throw notFound(id); }
	}

	private static DatasetImportException notFound(String id) {
		return new DatasetImportException(DatasetErrorCode.MODEL_PREVIEW_NOT_FOUND,
				"Model preview does not exist: " + id);
	}

	private static void deleteTree(Path root) throws IOException {
		if (root == null || !Files.exists(root)) return;
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.deleteIfExists(file);
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
				if (error != null) throw error;
				Files.deleteIfExists(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
