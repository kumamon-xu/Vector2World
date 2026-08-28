package org.osm2world.buildingtiler.api;

import java.util.List;

public record CreateGenerationJobRequest(
		String datasetId,
		String heightField,
		String heightUnit,
		String invalidPolicy,
		Double maximumHeightMeters,
		String ruleVersion,
		String roofMode,
		String stylePreset,
		Double floorHeightMeters,
		Double roofHeightRatio,
		Double minimumRoofHeightMeters,
		Double maximumRoofHeightMeters,
		Double minimumBodyHeightMeters,
		Double minimumPitchedBuildingHeightMeters,
		Double maximumPitchedBuildingHeightMeters,
		Integer zoom,
		List<Integer> lods,
		Integer workerCount,
		Integer queueCapacity,
		Integer transientRetryCount,
		Double crossTileBufferMeters,
		Integer largeBuildingTileSpanWarning,
		List<String> outputFormats,
		Long variantSeed,
		Boolean allowPartialResult,
		Integer maxFailedTiles,
		Double maxFailedTileRatio,
		Integer maxFailedBuildings,
		Double maxFailedBuildingRatio) {
}
