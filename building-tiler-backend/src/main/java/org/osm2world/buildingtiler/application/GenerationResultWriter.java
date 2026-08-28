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
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.osm2world.buildingtiler.gis.DatasetReadResult;
import org.osm2world.buildingtiler.modeling.StableStyleHash;
import org.osm2world.buildingtiler.modeling.StylePresetCatalog;
import org.osm2world.buildingtiler.osm2world.Osm2WorldConfigFactory;
import org.osm2world.buildingtiler.osm2world.ModelingLedgerEntry;
import org.osm2world.buildingtiler.osm2world.ModelingLedgerEntry.Status;
import org.osm2world.buildingtiler.osm2world.ModelingLedgerEntry.Stage;
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
			List<TileFailure> failures, boolean incomplete, int failedBuildings, List<String> warnings,
			TilesetValidator.ValidationResult validation, Instant buildStarted, Instant buildFinished,
			Map<String, Object> resourceMetrics)
			throws IOException {
		List<TileRenderResult> ordered = successes.stream()
				.sorted(Comparator.comparing(TileRenderResult::tile)).toList();
		var contents = ordered.stream().flatMap(result -> result.contents().stream())
				.sorted(Comparator.comparing(value -> value.tile() + "/" + value.lod())).toList();
		List<ModelingLedgerEntry> ledgerEntries = new ArrayList<>(ordered.stream()
				.flatMap(result -> result.modelingLedger().stream())
				.toList());
		Map<String, TileFailure> failuresByTile = new LinkedHashMap<>();
		for (TileFailure failure : failures) failuresByTile.put(failure.tile(), failure);
		String failedStyleHash = new StableStyleHash().configHash(job.spec().modelingConfig());
		for (var tile : plan.tiles()) {
			TileFailure failure = failuresByTile.get(tile.tile().toString());
			if (failure == null) continue;
			if (!java.util.Set.copyOf(failure.failedFeatureIds()).equals(tile.features().stream()
					.map(feature -> feature.id()).collect(java.util.stream.Collectors.toSet()))) {
				throw new IOException("MODELING_LEDGER_INCOMPLETE: failed tile feature IDs do not match " + failure.tile());
			}
			for (var feature : tile.features()) {
				List<Polygon> parts = polygonParts(feature.geometryWgs84());
				for (int partIndex = 0; partIndex < Math.max(1, parts.size()); partIndex++) {
					Polygon part = parts.isEmpty() ? null : parts.get(partIndex);
					ledgerEntries.add(new ModelingLedgerEntry(feature.id(), feature.id() + "/part/" + partIndex,
							partIndex, part == null ? 0 : part.getNumInteriorRing(), failure.tile(),
							feature.heightMeters(), failedStyleHash, Stage.TILE_EXECUTION, Status.FAILED_TILE,
							failure.category(), failure.message()));
				}
			}
		}
		ledgerEntries.sort(Comparator.comparing(ModelingLedgerEntry::tileId)
				.thenComparing(ModelingLedgerEntry::sourceFeatureId)
				.thenComparingInt(ModelingLedgerEntry::partIndex));
		ledgerEntries = List.copyOf(ledgerEntries);
		if (ledgerEntries.stream().anyMatch(entry -> entry.status() == Status.PENDING)) {
			throw new IOException("MODELING_LEDGER_INCOMPLETE: ledger contains a non-terminal part");
		}
		Map<String, Long> ledgerStatusCounts = new LinkedHashMap<>();
		for (Status status : Status.values()) {
			ledgerStatusCounts.put(status.name(), ledgerEntries.stream()
					.filter(entry -> entry.status() == status).count());
		}
		long modeledParts = ledgerStatusCounts.get(Status.MODELED.name());
		long missingParts = ledgerStatusCounts.get(Status.MISSING_UNATTRIBUTED.name());
		Map<String, Object> ledgerSummary = new LinkedHashMap<>();
		ledgerSummary.put("path", "modeling-ledger.json");
		ledgerSummary.put("inputParts", ledgerEntries.size());
		ledgerSummary.put("modeledParts", modeledParts);
		ledgerSummary.put("missingUnattributed", missingParts);
		ledgerSummary.put("statusCounts", ledgerStatusCounts);
		Map<String, Object> ledgerDocument = new LinkedHashMap<>();
		ledgerDocument.put("schemaVersion", "1.0");
		ledgerDocument.put("jobId", job.id().toString());
		ledgerDocument.put("summary", ledgerSummary);
		ledgerDocument.put("entries", ledgerEntries);
		writeJson(staging.resolve("modeling-ledger.json"), ledgerDocument);
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
		var styleBundle = Osm2WorldConfigFactory.currentBundleInfo();
		manifest.put("styleBundleVersion", styleBundle.version());
		manifest.put("styleBundleSha256", styleBundle.sha256());
		manifest.put("configHash", configHash);
		manifest.put("sourceFormat", dataset.metadata().format());
		manifest.put("sourceCrs", dataset.metadata().sourceCrs());
		manifest.put("crsSource", dataset.metadata().crsSource());
		manifest.put("sourceEncoding", dataset.metadata().sourceEncoding());
		manifest.put("archiveEntryEncoding", dataset.metadata().archiveEntryEncoding());
		manifest.put("archiveEntryEncodingFallback", dataset.metadata().archiveEntryEncodingFallback());
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
		manifest.put("modelingLedger", ledgerSummary);
		manifest.put("incomplete", incomplete);
		manifest.put("failedBuildings", failedBuildings);
		manifest.put("deliveryPolicy", deliveryPolicy(job.spec().deliveryPolicy()));
		manifest.put("buildTime", buildFinished.toString());
		writeJson(staging.resolve("manifest.json"), manifest);
		if (incomplete) {
			Files.writeString(staging.resolve("INCOMPLETE-RESULT.txt"),
					"INCOMPLETE RESULT - DO NOT PRESENT AS A COMPLETE DATASET\n"
					+ "failedTiles=" + failures.size() + "\nfailedBuildings=" + failedBuildings + "\n",
					UTF_8);
		}

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
		report.put("incomplete", incomplete);
		report.put("failedBuildings", failedBuildings);
		report.put("deliveryPolicy", deliveryPolicy(job.spec().deliveryPolicy()));
		report.put("successfulTileContents", contents.size());
		report.put("modeledBuildings", ordered.stream().mapToInt(TileRenderResult::modeledBuildings).sum());
		report.put("modeledParts", modeledParts);
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
		report.put("modelingLedger", ledgerSummary);
		report.put("modelingFailureSamples", ledgerEntries.stream()
				.filter(entry -> entry.status() != Status.MODELED).limit(100).toList());
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
		artifacts.add(artifact(staging, "modeling-ledger", "modeling-ledger.json", "application/json"));
		if (incomplete) artifacts.add(artifact(staging, "incomplete-result-warning",
				"INCOMPLETE-RESULT.txt", "text/plain"));
		return new WriteResult(outputBytes, contentBytes, List.copyOf(artifacts), configHash);
	}

	private static Map<String, Object> heightMapping(ManagedGenerationJob job) {
		var mapping = job.spec().heightMapping();
		return Map.of("fieldName", mapping.fieldName(), "unit", mapping.unit().name().toLowerCase(),
				"invalidPolicy", mapping.invalidPolicy().name(),
				"maximumHeightMeters", mapping.maximumHeightMeters());
	}

	private static List<Polygon> polygonParts(Geometry geometry) {
		if (geometry instanceof Polygon polygon) return List.of(polygon);
		if (geometry instanceof MultiPolygon multiPolygon) {
			List<Polygon> parts = new ArrayList<>(multiPolygon.getNumGeometries());
			for (int index = 0; index < multiPolygon.getNumGeometries(); index++) {
				parts.add((Polygon)multiPolygon.getGeometryN(index));
			}
			return parts;
		}
		return List.of();
	}

	private static Map<String, Object> deliveryPolicy(DeliveryPolicy policy) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("allowPartialResult", policy.allowPartialResult());
		result.put("maxFailedTiles", policy.maxFailedTiles());
		result.put("maxFailedTileRatio", policy.maxFailedTileRatio());
		result.put("maxFailedBuildings", policy.maxFailedBuildings());
		result.put("maxFailedBuildingRatio", policy.maxFailedBuildingRatio());
		return result;
	}

	static String configHash(GenerationJobSpec spec) {
		String modeling = new StableStyleHash().configHash(spec.modelingConfig());
		var tiling = spec.tilingConfig();
		String canonical = String.join("|", modeling, Integer.toString(tiling.zoom()), tiling.lods().toString(),
				Integer.toString(tiling.workerCount()), Integer.toString(tiling.queueCapacity()),
				Integer.toString(tiling.transientRetryCount()), Double.toString(tiling.crossTileBufferMeters()),
				Integer.toString(tiling.largeBuildingTileSpanWarning()), tiling.outputFormats().toString(),
				spec.deliveryPolicy().toString());
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
