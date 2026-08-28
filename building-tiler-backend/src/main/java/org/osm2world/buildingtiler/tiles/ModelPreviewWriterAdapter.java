package org.osm2world.buildingtiler.tiles;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static org.osm2world.output.common.compression.Compression.NONE;
import static org.osm2world.output.gltf.GltfFlavor.GLB;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.locationtech.jts.geom.Envelope;
import org.osm2world.buildingtiler.application.UpstreamBaseline;
import org.osm2world.buildingtiler.domain.BuildingFeature;
import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.modeling.StableStyleHash;
import org.osm2world.buildingtiler.modeling.StylePresetCatalog;
import org.osm2world.buildingtiler.osm2world.Osm2WorldEngineAdapter;
import org.osm2world.output.tileset.TilesetOutput;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class ModelPreviewWriterAdapter {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Osm2WorldEngineAdapter engine;
	private final TilesetValidator validator;

	public ModelPreviewWriterAdapter(Osm2WorldEngineAdapter engine, TilesetValidator validator) {
		this.engine = engine;
		this.validator = validator;
	}

	public PreviewWriteResult write(List<BuildingFeature> features, ModelingConfig config,
			Path outputDirectory, Map<String, List<String>> bucketCoverage, String selectionHash) throws IOException {
		if (features == null || features.isEmpty()) throw new IOException("Preview sample contains no buildings");
		Path output = outputDirectory.toAbsolutePath().normalize();
		if (Files.exists(output)) throw new IOException("Preview output already exists: " + output);
		if (output.getParent() == null) throw new IOException("Preview output needs a parent directory");
		Files.createDirectories(output.getParent());
		Path staging = output.resolveSibling(output.getFileName() + ".staging-" + UUID.randomUUID());
		Files.createDirectory(staging);
		long writeStarted = System.nanoTime();
		try {
			var generated = engine.generateRegion(output.getFileName().toString(), features, config);
			if (generated.empty()) throw new IOException("OSM2World could not model any preview building");
			Path tileset = staging.resolve("tileset.json");
			TilesetOutput writer = new TilesetOutput(tileset.toFile(), GLB, NONE,
					generated.projection(), generated.boundary());
			writer.setConfiguration(generated.configuration());
			writer.outputScene(generated.scene());
			if (!Files.isRegularFile(tileset) || !Files.isRegularFile(staging.resolve("tileset.glb"))) {
				throw new IOException("OSM2World did not write the preview tileset and GLB");
			}
			new TilesetRegionReconciler().expandToFinalVertices(tileset, staging.resolve("tileset.glb"));
			TilesetValidator.ValidationResult validation = validator.validate(staging);
			if (!validation.valid()) throw new IOException("Preview validation failed: " + validation.errors());
			Envelope bounds = new Envelope();
			features.forEach(feature -> bounds.expandToInclude(feature.geometryWgs84().getEnvelopeInternal()));
			List<Double> focusBounds = focusBounds(features);
			List<String> styleHashes = generated.styles().stream().map(value -> value.style().outputHash())
					.sorted().toList();
			String outputHash = StableStyleHash.hash(String.join("\n", styleHashes));
			Map<String, Object> report = new LinkedHashMap<>();
			report.put("schemaVersion", "1.0");
			report.put("generatedAt", Instant.now().toString());
			report.put("applicationVersion", UpstreamBaseline.APPLICATION_VERSION);
			report.put("osm2worldVersion", UpstreamBaseline.OSM2WORLD_VERSION);
			report.put("osm2worldCommit", UpstreamBaseline.OSM2WORLD_COMMIT);
			report.put("ruleVersion", config.ruleVersion().value());
			report.put("presetVersion", StylePresetCatalog.PRESET_VERSION);
			report.put("styleBundle", engine.styleBundleInfo());
			report.put("stylePreset", config.stylePreset().value());
			report.put("roofMode", config.roofMode().name());
			report.put("lod", config.lod());
			report.put("selectionHash", selectionHash);
			report.put("ruleOutputHash", outputHash);
			report.put("selectedBuildings", features.size());
			report.put("modeledBuildings", generated.modeledFeatures());
			report.put("meshCount", generated.meshCount());
			report.put("bucketCoverage", bucketCoverage);
			report.put("boundsWgs84", List.of(bounds.getMinX(), bounds.getMinY(), bounds.getMaxX(), bounds.getMaxY()));
			report.put("focusBoundsWgs84", focusBounds);
			report.put("warnings", generated.warnings());
			report.put("featureFailures", generated.failures());
			report.put("engineMetrics", generated.metrics());
			report.put("stableSceneMetrics", Map.of(
					"meshCount", generated.meshCount(),
					"vertexCount", generated.metrics().vertexCount(),
					"triangleCount", generated.metrics().triangleCount(),
					"minimumY", generated.metrics().minimumY(),
					"maximumY", generated.metrics().maximumY()));
			report.put("volatileFields", List.of("generatedAt", "engineMetrics.totalNanos",
					"engineMetrics.ruleNanos", "engineMetrics.conversionNanos"));
			report.put("validation", validation);
			report.put("styles", generated.styles().stream()
					.sorted(Comparator.comparing(value -> value.style().outputHash()))
					.map(ModelPreviewWriterAdapter::styleReport)
					.toList());
			writeJson(staging.resolve("preview-report.json"), report);
			long bytes = directoryBytes(staging);
			try {
				Files.move(staging, output, ATOMIC_MOVE);
			} catch (IOException atomicMoveFailure) {
				Files.move(staging, output, StandardCopyOption.REPLACE_EXISTING);
			}
			return new PreviewWriteResult(output, features.size(), generated.modeledFeatures(),
					generated.meshCount(), outputHash, selectionHash, styleHashes,
					List.of(bounds.getMinX(), bounds.getMinY(), bounds.getMaxX(), bounds.getMaxY()),
					generated.warnings(), generated.failures(), generated.metrics(), validation,
					bytes, System.nanoTime() - writeStarted);
		} catch (Exception exception) {
			deleteTree(staging);
			if (exception instanceof IOException ioException) throw ioException;
			throw new IOException("Model preview generation failed: " + exception.getMessage(), exception);
		}
	}

	private static List<Double> focusBounds(List<BuildingFeature> features) {
		Envelope focus = features.stream()
				.max(Comparator.comparingDouble(feature -> feature.geometryWgs84().getArea()))
				.orElseThrow()
				.geometryWgs84().getEnvelopeInternal();
		double longitudePadding = Math.max(focus.getWidth() * 2.0, 0.00025);
		double latitudePadding = Math.max(focus.getHeight() * 2.0, 0.00025);
		return List.of(focus.getMinX() - longitudePadding, focus.getMinY() - latitudePadding,
				focus.getMaxX() + longitudePadding, focus.getMaxY() + latitudePadding);
	}

	private static Map<String, Object> styleReport(org.osm2world.buildingtiler.domain.StyledBuilding value) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("featureId", value.feature().id());
		result.put("heightMeters", value.style().heightMeters());
		result.put("levels", value.style().levels());
		result.put("roofShape", value.style().roofShape());
		result.put("roofHeightMeters", value.style().roofHeightMeters());
		result.put("wallMaterial", value.style().wallMaterial());
		result.put("roofMaterial", value.style().roofMaterial());
		result.put("wallColor", value.style().wallColor());
		result.put("roofColor", value.style().roofColor());
		result.put("windows", value.style().windows());
		result.put("outputHash", value.style().outputHash());
		result.put("reasons", value.style().reasons());
		result.put("provenance", value.style().provenance());
		return result;
	}

	private static void writeJson(Path file, Object value) throws IOException {
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(value, writer);
		}
	}

	private static long directoryBytes(Path root) throws IOException {
		try (var files = Files.walk(root)) {
			return files.filter(Files::isRegularFile).mapToLong(path -> {
				try { return Files.size(path); }
				catch (IOException exception) { throw new IllegalStateException(exception); }
			}).sum();
		}
	}

	private static void deleteTree(Path root) {
		if (root == null || !Files.exists(root)) return;
		try {
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
		} catch (IOException ignored) {
			// The preview lifecycle cleanup will retry stale staging directories.
		}
	}

	public record PreviewWriteResult(
			Path outputDirectory,
			int selectedBuildings,
			int modeledBuildings,
			int meshCount,
			String ruleOutputHash,
			String selectionHash,
			List<String> styleHashes,
			List<Double> boundsWgs84,
			List<String> warnings,
			List<Osm2WorldEngineAdapter.FeatureFailure> failures,
			Osm2WorldEngineAdapter.EngineMetrics engineMetrics,
			TilesetValidator.ValidationResult validation,
			long outputBytes,
			long totalNanos) {
	}
}
