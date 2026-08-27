package org.osm2world.buildingtiler.domain;

import java.util.List;
import java.util.Map;

public record BuildingStyle(
		double heightMeters,
		int levels,
		String roofShape,
		double roofHeightMeters,
		String wallMaterial,
		String roofMaterial,
		String wallColor,
		String roofColor,
		boolean windows,
		int variantBucket,
		StylePresetId preset,
		RuleVersion ruleVersion,
		String outputHash,
		List<String> reasons,
		Map<String, String> provenance) {

	public BuildingStyle {
		if (!Double.isFinite(heightMeters) || heightMeters <= 0) {
			throw new IllegalArgumentException("Style height must be finite and positive");
		}
		if (levels < 1) throw new IllegalArgumentException("Style levels must be positive");
		if (roofShape == null || roofShape.isBlank()) throw new IllegalArgumentException("Roof shape is required");
		if (!Double.isFinite(roofHeightMeters) || roofHeightMeters < 0 || roofHeightMeters >= heightMeters) {
			throw new IllegalArgumentException("Roof height must be non-negative and below total height");
		}
		if (wallMaterial == null || wallMaterial.isBlank() || roofMaterial == null || roofMaterial.isBlank()) {
			throw new IllegalArgumentException("Wall and roof materials are required");
		}
		if (preset == null || ruleVersion == null || outputHash == null || outputHash.isBlank()) {
			throw new IllegalArgumentException("Preset, rule version and output hash are required");
		}
		reasons = reasons == null ? List.of() : List.copyOf(reasons);
		provenance = provenance == null ? Map.of() : Map.copyOf(provenance);
	}
}
