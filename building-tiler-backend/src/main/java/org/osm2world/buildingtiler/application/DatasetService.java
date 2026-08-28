package org.osm2world.buildingtiler.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.gis.DatasetErrorCode;
import org.osm2world.buildingtiler.gis.DatasetImportException;
import org.osm2world.buildingtiler.gis.DatasetInspection;
import org.osm2world.buildingtiler.gis.DatasetReadResult;
import org.osm2world.buildingtiler.gis.GeoJsonDatasetReader;
import org.osm2world.buildingtiler.gis.ImportOptions;
import org.osm2world.buildingtiler.gis.ImportDeadline;
import org.osm2world.buildingtiler.gis.SafeDatasetArchives;
import org.osm2world.buildingtiler.gis.ShapefileDatasetReader;
import org.osm2world.buildingtiler.gis.UploadFormat;
import org.osm2world.buildingtiler.gis.UploadInspector;
import org.osm2world.buildingtiler.gis.UploadLimits;

public final class DatasetService {

	private final Path storageRoot;
	private final UploadLimits limits;
	private final Map<UUID, ManagedDataset> datasets = new ConcurrentHashMap<>();
	private final GeoJsonDatasetReader geoJsonReader = new GeoJsonDatasetReader();
	private final ShapefileDatasetReader shapefileReader = new ShapefileDatasetReader();

	public DatasetService(Path storageRoot, UploadLimits limits) {
		this.storageRoot = storageRoot.toAbsolutePath().normalize();
		this.limits = limits == null ? UploadLimits.defaults() : limits;
	}

	public ManagedDataset upload(String originalFileName, String contentType, long declaredSize,
			InputStream content, ImportOptions options) throws IOException {
		if (content == null || declaredSize == 0) {
			throw new DatasetImportException(DatasetErrorCode.EMPTY_UPLOAD, "Uploaded file is empty");
		}
		if (declaredSize > limits.maximumUploadBytes()) {
			throw new DatasetImportException(DatasetErrorCode.UPLOAD_TOO_LARGE,
					"Upload exceeds " + limits.maximumUploadBytes() + " bytes");
		}
		ImportDeadline deadline = options.newDeadline();
		deadline.check("upload initialization");
		ensureStorageRoot();
		UUID id = UUID.randomUUID();
		Path workDirectory = storageRoot.resolve("dataset-" + id).normalize();
		if (!workDirectory.startsWith(storageRoot)) throw new IllegalStateException("Dataset path escaped storage root");
		try {
			Files.createDirectory(workDirectory);
		} catch (IOException exception) {
			throw storageFailure(exception);
		}

		ManagedDataset dataset = new ManagedDataset(id, workDirectory, safeDisplayName(originalFileName),
				options.timeout());
		datasets.put(id, dataset);
		try {
			String suffix = safeSuffix(originalFileName);
			Path stored = workDirectory.resolve("upload" + suffix);
			copyLimited(content, stored, deadline);
			deadline.check("format detection");
			UploadFormat format = UploadInspector.detect(originalFileName, contentType, stored);
			deadline.check("format detection");
			dataset.status(DatasetStatus.INSPECTING);
			DatasetInspection inspection;
			Path normalizedStore = workDirectory.resolve("normalized-features.v2w");
			if (format == UploadFormat.GEOJSON) {
				inspection = geoJsonReader.inspectToStore(stored, options, deadline, normalizedStore,
						limits.maximumZipUncompressedBytes());
			} else {
				Path unpacked = workDirectory.resolve("unpacked");
				var extraction = SafeDatasetArchives.extract(stored, unpacked, limits, deadline);
				deadline.check("shapefile selection");
				Path shapefile = SafeDatasetArchives.selectShapefile(extraction, options.selectedLayer());
				ImportOptions shapefileOptions = new ImportOptions(options.explicitCrs(), null,
						options.dbfCharset(), options.timeout(), options.repairWarningAreaRatio(),
						options.repairRejectAreaRatio());
				inspection = shapefileReader.inspectToStore(shapefile, shapefileOptions, deadline,
						normalizedStore, limits.maximumZipUncompressedBytes())
						.withArchiveEntryEncoding(extraction.entryNameEncoding(), extraction.legacyEncodingFallback());
			}
			dataset.ready(inspection);
			return dataset;
		} catch (Exception exception) {
			dataset.status(DatasetStatus.FAILED);
			datasets.remove(id, dataset);
			try {
				deleteTree(workDirectory);
			} catch (IOException cleanupFailure) {
				exception.addSuppressed(cleanupFailure);
			}
			if (exception instanceof IOException ioException) throw ioException;
			throw new DatasetImportException(DatasetErrorCode.STORAGE_UNAVAILABLE,
					"Dataset import failed: " + exception.getMessage(), exception);
		}
	}

	public ManagedDataset get(String id) throws DatasetImportException {
		UUID uuid;
		try { uuid = UUID.fromString(id); }
		catch (RuntimeException exception) { throw notFound(id); }
		ManagedDataset dataset = datasets.get(uuid);
		if (dataset == null || dataset.status() != DatasetStatus.READY) throw notFound(id);
		dataset.touch();
		return dataset;
	}

	public DatasetReadResult materialize(String id, HeightMapping mapping) throws DatasetImportException {
		ManagedDataset dataset = get(id);
		DatasetReadResult result = dataset.inspection().materialize(mapping,
				ImportDeadline.start(dataset.importTimeout()));
		dataset.heightMapping(mapping);
		return result;
	}

	public boolean delete(String id) throws IOException {
		UUID uuid;
		try { uuid = UUID.fromString(id); }
		catch (RuntimeException exception) { return false; }
		ManagedDataset dataset = datasets.remove(uuid);
		if (dataset == null) return false;
		dataset.status(DatasetStatus.DELETING);
		deleteTree(dataset.workDirectory());
		dataset.status(DatasetStatus.DELETED);
		return true;
	}

	public int cleanupExpired(Instant now) {
		int removed = 0;
		for (ManagedDataset dataset : datasets.values()) {
			if (dataset.lastAccessedAt().plus(limits.datasetTtl()).isBefore(now)
					&& datasets.remove(dataset.id(), dataset)) {
				try {
					dataset.status(DatasetStatus.DELETING);
					deleteTree(dataset.workDirectory());
					dataset.status(DatasetStatus.DELETED);
					removed++;
				} catch (IOException ignored) {
					// A later startup cleanup can retry an orphaned random directory.
				}
			}
		}
		if (Files.isDirectory(storageRoot)) {
			try (var directories = Files.list(storageRoot)) {
				for (Path directory : directories.filter(Files::isDirectory)
						.filter(path -> path.getFileName().toString().startsWith("dataset-")).toList()) {
					boolean live = datasets.values().stream().anyMatch(dataset -> dataset.workDirectory().equals(directory));
					if (live) continue;
					try {
						Instant modified = Files.getLastModifiedTime(directory).toInstant();
						if (modified.plus(limits.datasetTtl()).isBefore(now)) {
							deleteTree(directory);
							removed++;
						}
					} catch (IOException ignored) {
						// Cleanup is best effort and will retry on the next scheduled pass.
					}
				}
			} catch (IOException ignored) {
				// Storage can be temporarily unavailable; imports still report their own stable error.
			}
		}
		return removed;
	}

	public int size() { return datasets.size(); }

	public Path managedDirectory(String id) throws DatasetImportException { return get(id).workDirectory(); }

	public Path storageRoot() { return storageRoot; }

	private void ensureStorageRoot() throws DatasetImportException {
		try {
			Files.createDirectories(storageRoot);
			if (!Files.isDirectory(storageRoot) || !Files.isWritable(storageRoot)) {
				throw new IOException("Storage root is not a writable directory");
			}
		} catch (IOException exception) {
			throw storageFailure(exception);
		}
	}

	private void copyLimited(InputStream source, Path target, ImportDeadline deadline) throws IOException {
		long copied = 0;
		try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = source.read(buffer)) >= 0) {
				deadline.check("upload copy");
				copied += count;
				if (copied > limits.maximumUploadBytes()) {
					throw new DatasetImportException(DatasetErrorCode.UPLOAD_TOO_LARGE,
							"Upload exceeds " + limits.maximumUploadBytes() + " bytes");
				}
				output.write(buffer, 0, count);
			}
		}
		deadline.check("upload copy");
		if (copied == 0) throw new DatasetImportException(DatasetErrorCode.EMPTY_UPLOAD, "Uploaded file is empty");
	}

	private static String safeSuffix(String originalName) {
		String lower = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
		for (String suffix : new String[] { ".geojson", ".json", ".zip" }) {
			if (lower.endsWith(suffix)) return suffix;
		}
		return ".upload";
	}

	private static String safeDisplayName(String originalName) {
		if (originalName == null || originalName.isBlank()) return "upload";
		String normalized = originalName.replace('\\', '/');
		int slash = normalized.lastIndexOf('/');
		String leaf = slash < 0 ? normalized : normalized.substring(slash + 1);
		return leaf.isBlank() ? "upload" : leaf;
	}

	private static DatasetImportException notFound(String id) {
		return new DatasetImportException(DatasetErrorCode.DATASET_NOT_FOUND, "Dataset does not exist: " + id);
	}

	private static DatasetImportException storageFailure(IOException exception) {
		return new DatasetImportException(DatasetErrorCode.STORAGE_UNAVAILABLE,
				"Dataset storage is unavailable: " + exception.getMessage(), exception);
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
