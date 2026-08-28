package org.osm2world.buildingtiler.osm2world;

/** One immutable source-feature part as it moves through the modeling and final-GLB pipeline. */
public record ModelingLedgerEntry(
		String sourceFeatureId,
		String partId,
		int partIndex,
		int holeCount,
		String tileId,
		double expectedHeightMeters,
		String styleHash,
		Stage stage,
		Status status,
		String reasonCode,
		String message) {

	public ModelingLedgerEntry {
		if (sourceFeatureId == null || sourceFeatureId.isBlank() || partId == null || partId.isBlank()) {
			throw new IllegalArgumentException("Ledger source feature and part ids are required");
		}
		if (partIndex < 0 || holeCount < 0 || tileId == null || tileId.isBlank()) {
			throw new IllegalArgumentException("Ledger part, hole and tile values are invalid");
		}
		if (!Double.isFinite(expectedHeightMeters) || expectedHeightMeters <= 0 || stage == null || status == null) {
			throw new IllegalArgumentException("Ledger height, stage and status are required");
		}
		styleHash = styleHash == null ? "" : styleHash;
		reasonCode = reasonCode == null ? "" : reasonCode;
		message = message == null ? "" : message;
	}

	public ModelingLedgerEntry transition(Stage nextStage, Status nextStatus, String code, String detail) {
		return new ModelingLedgerEntry(sourceFeatureId, partId, partIndex, holeCount, tileId,
				expectedHeightMeters, styleHash, nextStage, nextStatus, code, detail);
	}

	public enum Stage {
		RULE, MAP_BUILD, O2W_CONVERSION, GLTF_EXPORT, FINAL_GLTF, TILE_EXECUTION
	}

	public enum Status {
		PENDING, MODELED, REJECTED_INPUT, FILTERED_BY_POLICY, FAILED_RULE,
		FAILED_MAP_BUILD, FAILED_O2W_CONVERSION, FAILED_GLTF_EXPORT, MISSING_UNATTRIBUTED,
		FAILED_TILE
	}
}
