package org.osm2world.buildingtiler.api;

import org.osm2world.buildingtiler.domain.ModelingConfig;

public record ModelingConfigView(
		String ruleVersion,
		String roofMode,
		String stylePreset,
		double floorHeightMeters,
		double roofHeightRatio,
		double minimumRoofHeightMeters,
		double maximumRoofHeightMeters,
		double minimumBodyHeightMeters,
		double minimumPitchedBuildingHeightMeters,
		double maximumPitchedBuildingHeightMeters,
		int lod,
		int sampleSize,
		long variantSeed,
		String configHash) {

	public static ModelingConfigView from(ModelingConfig config, String configHash) {
		return new ModelingConfigView(config.ruleVersion().value(), config.roofMode().name(),
				config.stylePreset().value(), config.floorHeightMeters(), config.roofHeightRatio(),
				config.minimumRoofHeightMeters(), config.maximumRoofHeightMeters(),
				config.minimumBodyHeightMeters(), config.minimumPitchedBuildingHeightMeters(),
				config.maximumPitchedBuildingHeightMeters(), config.lod(), config.previewSampleSize(),
				config.variantSeed(), configHash);
	}
}
