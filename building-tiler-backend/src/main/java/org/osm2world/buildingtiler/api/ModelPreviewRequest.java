package org.osm2world.buildingtiler.api;

public record ModelPreviewRequest(
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
		Integer lod,
		Integer sampleSize,
		Long variantSeed) {
}
