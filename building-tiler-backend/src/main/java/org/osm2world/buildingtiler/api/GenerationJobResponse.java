package org.osm2world.buildingtiler.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.osm2world.buildingtiler.application.JobArtifact;
import org.osm2world.buildingtiler.application.ManagedGenerationJob;
import org.osm2world.buildingtiler.application.TileFailure;
import org.osm2world.buildingtiler.modeling.StableStyleHash;

public record GenerationJobResponse(
		String schemaVersion,
		String id,
		String datasetId,
		String state,
		Instant createdAt,
		Instant updatedAt,
		Instant expiresAt,
		int completedTiles,
		int totalTiles,
		double progress,
		String error,
		ModelingConfigView modelingConfig,
		Map<String, Object> tilingConfig,
		Integer successfulTiles,
		Integer failedTiles,
		Integer modeledBuildings,
		Long outputBytes,
		String ownershipHash,
		List<String> warnings,
		List<TileFailure> tileFailures,
		List<JobArtifact> artifacts,
		Map<String, String> links) {

	public static GenerationJobResponse from(ManagedGenerationJob job) {
		var result = job.result();
		var tiling = job.spec().tilingConfig();
		String id = job.id().toString();
		return new GenerationJobResponse("1.0", id, job.spec().datasetId(), job.state().name(),
				job.createdAt(), job.updatedAt(), job.expiresAt(), job.completedTiles(), job.totalTiles(),
				job.totalTiles() == 0 ? 0 : (double)job.completedTiles() / job.totalTiles(), job.error(),
				ModelingConfigView.from(job.spec().modelingConfig(),
						new StableStyleHash().configHash(job.spec().modelingConfig())),
				Map.of("zoom", tiling.zoom(), "lods", tiling.lods(), "workerCount", tiling.workerCount(),
						"queueCapacity", tiling.queueCapacity(), "transientRetryCount", tiling.transientRetryCount(),
						"crossTileBufferMeters", tiling.crossTileBufferMeters(),
						"largeBuildingTileSpanWarning", tiling.largeBuildingTileSpanWarning(),
						"outputFormats", tiling.outputFormats().stream().map(value -> value.value()).toList()),
				result == null ? null : result.successfulTiles(), result == null ? null : result.failedTiles(),
				result == null ? null : result.modeledBuildings(), result == null ? null : result.outputBytes(),
				result == null ? null : result.ownershipHash(), result == null ? List.of() : result.warnings(),
				result == null ? List.of() : result.tileFailures(), result == null ? List.of() : result.artifacts(),
				Map.of("self", "/api/jobs/" + id, "events", "/api/jobs/" + id + "/events",
						"tileset", "/api/jobs/" + id + "/files/tileset.json", "manifest", "/api/jobs/" + id + "/manifest",
						"report", "/api/jobs/" + id + "/report", "download", "/api/jobs/" + id + "/download",
						"diagnostics", "/api/jobs/" + id + "/diagnostics",
						"retryFailed", "/api/jobs/" + id + "/retry-failed"));
	}
}
