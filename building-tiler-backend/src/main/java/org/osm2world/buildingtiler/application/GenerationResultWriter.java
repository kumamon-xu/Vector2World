package org.osm2world.buildingtiler.application;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Envelope;
import org.osm2world.buildingtiler.gis.DatasetReadResult;
import org.osm2world.buildingtiler.modeling.StableStyleHash;
import org.osm2world.buildingtiler.modeling.StylePresetCatalog;
import org.osm2world.buildingtiler.tiles.TileOwnershipPlanner;
import org.osm2world.buildingtiler.tiles.TileRenderResult;
import org.osm2world.buildingtiler.product.ProductBuildInfo;
import org.osm2world.buildingtiler.tiles.TilesetValidator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class GenerationResultWriter {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public WriteResult write(Path staging, ManagedGenerationJob job, DatasetReadResult dataset,
			TileOwnershipPlanner.TilingPlan plan, List<TileRenderResult> successes,
			List<TileFailure> failures, List<String> warnings,
			TilesetValidator.ValidationResult validation, Instant buildStarted, Instant buildFinished,
			Map<String, Object> resourceMetrics)
			throws IOException {
		List<TileRenderResult> ordered = successes.stream()
				.sorted(Comparator.comparing(TileRenderResult::tile)).toList();
		var contents = ordered.stream().flatMap(result -> result.contents().stream())
				.sorted(Comparator.comparing(value -> value.tile() + "/" + value.lod())).toList();
		String configHash = configHash(job.spec());

		Map<String, Object> manifest = new LinkedHashMap<>();
		manifest.put("schemaVersion", "1.0");
		ProductBuildInfo product = ProductBuildInfo.current();
		manifest.put("applicationVersion", product.version());
		manifest.put("applicationBuildNumber", product.buildNumber());
		manifest.put("applicationGitSha", product.gitSha());
		manifest.put("applicationGitDirty", product.gitDirty());
		manifest.put("applicationBuildTime", product.buildTime());
		manifest.put("osm2worldVersion", UpstreamBaseline.OSM2WORLD_VERSION);
		manifest.put("osm2worldCommit", product.osm2worldCommit());
		manifest.put("ruleVersion", job.spec().modelingConfig().ruleVersion().value());
		manifest.put("presetVersion", StylePresetCatalog.PRESET_VERSION);
		manifest.put("configHash", configHash);
		manifest.put("sourceFormat", dataset.metadata().format());
		manifest.put("sourceCrs", dataset.metadata().sourceCrs());
		manifest.put("sourceEncoding", dataset.metadata().sourceEncoding());
		manifest.put("heightMapping", heightMapping(job));
		manifest.put("zoom", job.spec().tilingConfig().zoom());
		manifest.put("lods", job.spec().tilingConfig().lods());
		manifest.put("outputFormats", job.spec().tilingConfig().outputFormats().stream()
				.map(value -> value.value()).toList());
		manifest.put("crossTileStrategy", "centroid-owner/full-footprint/no-clip");
		manifest.put("crossTileBufferMeters", job.spec().tilingConfig().crossTileBufferMeters());
		manifest.put("boundsWgs84", bounds(plan.boundsWgs84()));
		manifest.put("ownershipHash", plan.ownershipHash());
		manifest.put("tileContents", contents);
		manifest.put("buildTime", buildFinished.toString());
		writeJson(staging.resolve("manifest.json"), manifest);

		long contentBytes = directoryBytes(staging);
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("schemaVersion", "1.0");
		report.put("jobId", job.id().toString());
		report.put("state", failures.isEmpty() && warnings.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_WARNINGS");
		report.put("buildStarted", buildStarted.toString());
		report.put("buildFinished", buildFinished.toString());
		report.put("elapsedMillis", Math.max(0, buildFinished.toEpochMilli() - buildStarted.toEpochMilli()));
		report.put("inputFeatures", dataset.metadata().featureCount());
		report.put("validBuildings", dataset.metadata().validBuildings());
		report.put("skippedInvalidHeight", dataset.metadata().skippedInvalidHeight());
		report.put("skippedInvalidGeometry", dataset.metadata().skippedInvalidGeometry());
		report.put("plannedTiles", plan.tiles().size());
		report.put("successfulTiles", ordered.size());
		report.put("failedTiles", failures.size());
		report.put("successfulTileContents", contents.size());
		report.put("modeledBuildings", ordered.stream().mapToInt(TileRenderResult::modeledBuildings).sum());
		report.put("meshCount", ordered.stream().mapToInt(TileRenderResult::meshCount).sum());
		report.put("vertexCount", ordered.stream().mapToLong(TileRenderResult::vertexCount).sum());
		report.put("triangleCount", ordered.stream().mapToLong(TileRenderResult::triangleCount).sum());
		report.put("crossTileBuildings", plan.crossTileBuildings());
		report.put("largeBuildings", plan.largeBuildings());
		report.put("ownershipHash", plan.ownershipHash());
		report.put("configHash", configHash);
		report.put("contentBytes", contentBytes);
		report.put("outputBytes", 0L);
		report.put("quantization", validation.extensionsUsed().contains("KHR_mesh_quantization")
				? "KHR_mesh_quantization" : "NOT_EMITTED_BY_LOCKED_UPSTREAM");
		report.put("tileResults", ordered);
		report.put("tileFailures", failures);
		report.put("warnings", warnings);
		report.put("validation", validation);
		report.put("resourceMetrics", resourceMetrics == null ? Map.of() : resourceMetrics);
		report.put("volatileFields", List.of("jobId", "buildStarted", "buildFinished", "elapsedMillis",
				"tileResults.elapsedNanos"));
		Path reportFile = staging.resolve("generation-report.json");
		long outputBytes = convergeOutputBytes(staging, reportFile, report);

		List<JobArtifact> artifacts = new ArrayList<>();
		artifacts.add(artifact(staging, "tileset", "tileset.json", "application/json"));
		artifacts.add(artifact(staging, "manifest", "manifest.json", "application/json"));
		artifacts.add(artifact(staging, "generation-report", "generation-report.json", "application/json"));
		return new WriteResult(outputBytes, contentBytes, List.copyOf(artifacts), configHash);
	}

	private static Map<String, Object> heightMapping(ManagedGenerationJob job) {
		var mapping = job.spec().heightMapping();
		return Map.of("fieldName", mapping.fieldName(), "unit", mapping.unit().name().toLowerCase(),
				"invalidPolicy", mapping.invalidPolicy().name(),
				"maximumHeightMeters", mapping.maximumHeightMeters());
	}

	static String configHash(GenerationJobSpec spec) {
		String modeling = new StableStyleHash().configHash(spec.modelingConfig());
		var tiling = spec.tilingConfig();
		String canonical = String.join("|", modeling, Integer.toString(tiling.zoom()), tiling.lods().toString(),
				Integer.toString(tiling.workerCount()), Integer.toString(tiling.queueCapacity()),
				Integer.toString(tiling.transientRetryCount()), Double.toString(tiling.crossTileBufferMeters()),
				Integer.toString(tiling.largeBuildingTileSpanWarning()), tiling.outputFormats().toString());
		return StableStyleHash.hash(canonical);
	}

	private static List<Double> bounds(Envelope envelope) {
		return List.of(envelope.getMinX(), envelope.getMinY(), envelope.getMaxX(), envelope.getMaxY());
	}

	private static long convergeOutputBytes(Path root, Path reportFile, Map<String, Object> report)
			throws IOException {
		long expected = -1;
		for (int attempt = 0; attempt < 12; attempt++) {
			report.put("outputBytes", Math.max(0, expected));
			writeJson(reportFile, report);
			long actual = directoryBytes(root);
			if (actual == expected) return actual;
			expected = actual;
		}
		throw new IOException("generation-report outputBytes did not converge");
	}

	private static JobArtifact artifact(Path root, String name, String relative, String mediaType)
			throws IOException {
		Path file = root.resolve(relative);
		return new JobArtifact(name, relative, mediaType, Files.size(file));
	}

	private static long directoryBytes(Path root) throws IOException {
		try (var files = Files.walk(root)) {
			return files.filter(Files::isRegularFile).mapToLong(file -> {
				try { return Files.size(file); }
				catch (IOException exception) { throw new IllegalStateException(exception); }
			}).sum();
		} catch (IllegalStateException exception) {
			if (exception.getCause() instanceof IOException ioException) throw ioException;
			throw exception;
		}
	}

	private static void writeJson(Path file, Object value) throws IOException {
		try (Writer writer = Files.newBufferedWriter(file, UTF_8)) {
			GSON.toJson(value, writer);
		}
	}

	public record WriteResult(long outputBytes, long contentBytes,
			List<JobArtifact> artifacts, String configHash) {}
}
