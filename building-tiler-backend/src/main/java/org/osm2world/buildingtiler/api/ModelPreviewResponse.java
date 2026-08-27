package org.osm2world.buildingtiler.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.osm2world.buildingtiler.application.ManagedModelPreview;
import org.osm2world.buildingtiler.application.ModelPreviewService;
import org.osm2world.buildingtiler.modeling.StableStyleHash;
import org.osm2world.buildingtiler.osm2world.Osm2WorldEngineAdapter;

public record ModelPreviewResponse(
		String schemaVersion,
		String id,
		String datasetId,
		String status,
		Instant createdAt,
		Instant expiresAt,
		String disclaimer,
		Map<String, Object> heightMapping,
		ModelingConfigView config,
		int selectedBuildings,
		int modeledBuildings,
		int meshCount,
		String selectionHash,
		String ruleOutputHash,
		List<Double> boundsWgs84,
		Map<String, List<String>> bucketCoverage,
		List<String> warnings,
		List<Osm2WorldEngineAdapter.FeatureFailure> featureFailures,
		Map<String, Object> links) {

	public static ModelPreviewResponse from(ManagedModelPreview preview) {
		var result = preview.result();
		String id = preview.id().toString();
		return new ModelPreviewResponse("1.0", id, preview.datasetId(), preview.status().name(),
				preview.createdAt(), preview.expiresAt(), ModelPreviewService.DISCLAIMER,
				Map.of("fieldName", preview.heightMapping().fieldName(),
						"unit", preview.heightMapping().unit().name().toLowerCase(java.util.Locale.ROOT),
						"invalidPolicy", preview.heightMapping().invalidPolicy().name(),
						"maximumHeightMeters", preview.heightMapping().maximumHeightMeters()),
				ModelingConfigView.from(preview.config(), new StableStyleHash().configHash(preview.config())),
				result == null ? 0 : result.selectedBuildings(), result == null ? 0 : result.modeledBuildings(),
				result == null ? 0 : result.meshCount(), result == null ? null : result.selectionHash(),
				result == null ? null : result.ruleOutputHash(), result == null ? List.of() : result.boundsWgs84(),
				preview.bucketCoverage(), result == null ? List.of() : result.warnings(),
				result == null ? List.of() : result.failures(),
				Map.of("self", "/api/model-previews/" + id,
						"tileset", "/api/model-previews/" + id + "/files/tileset.json",
						"report", "/api/model-previews/" + id + "/files/preview-report.json"));
	}
}
