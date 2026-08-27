package org.osm2world.buildingtiler.domain;

public record ModelingConfig(
		RuleVersion ruleVersion,
		RoofMode roofMode,
		StylePresetId stylePreset,
		double floorHeightMeters,
		double roofHeightRatio,
		double minimumRoofHeightMeters,
		double maximumRoofHeightMeters,
		double minimumBodyHeightMeters,
		double minimumPitchedBuildingHeightMeters,
		double maximumPitchedBuildingHeightMeters,
		int lod,
		int previewSampleSize,
		long variantSeed,
		FootprintThresholds footprintThresholds) {

	public ModelingConfig {
		if (ruleVersion == null) ruleVersion = RuleVersion.CURRENT;
		if (!RuleVersion.CURRENT.equals(ruleVersion)) {
			throw new IllegalArgumentException("Unsupported rule version: " + ruleVersion.value());
		}
		if (roofMode == null) roofMode = RoofMode.AUTO_SIMPLE;
		if (stylePreset == null) stylePreset = StylePresetId.NEUTRAL_CITY;
		if (footprintThresholds == null) footprintThresholds = FootprintThresholds.defaults();
		range(floorHeightMeters, 2.0, 6.0, "floorHeightMeters");
		range(roofHeightRatio, 0.0, 0.5, "roofHeightRatio");
		range(minimumRoofHeightMeters, 0.0, 10.0, "minimumRoofHeightMeters");
		range(maximumRoofHeightMeters, 0.0, 20.0, "maximumRoofHeightMeters");
		if (minimumRoofHeightMeters > maximumRoofHeightMeters) {
			throw new IllegalArgumentException("minimumRoofHeightMeters cannot exceed maximumRoofHeightMeters");
		}
		range(minimumBodyHeightMeters, 1.0, 20.0, "minimumBodyHeightMeters");
		range(minimumPitchedBuildingHeightMeters, 2.0, 100.0, "minimumPitchedBuildingHeightMeters");
		range(maximumPitchedBuildingHeightMeters, 2.0, 1000.0, "maximumPitchedBuildingHeightMeters");
		if (minimumPitchedBuildingHeightMeters > maximumPitchedBuildingHeightMeters) {
			throw new IllegalArgumentException("minimumPitchedBuildingHeightMeters cannot exceed maximumPitchedBuildingHeightMeters");
		}
		if (lod < 0 || lod > 4) throw new IllegalArgumentException("lod must be between 0 and 4");
		if (previewSampleSize < 50 || previewSampleSize > 200) {
			throw new IllegalArgumentException("previewSampleSize must be between 50 and 200");
		}
	}

	public static ModelingConfig defaults() {
		return new ModelingConfig(RuleVersion.CURRENT, RoofMode.AUTO_SIMPLE, StylePresetId.NEUTRAL_CITY,
				3.2, 0.15, 0.8, 3.0, 2.5, 6.0, 30.0, 4, 100, 0x5632574cL,
				FootprintThresholds.defaults());
	}

	public ModelingConfig withLod(int value) {
		return new ModelingConfig(ruleVersion, roofMode, stylePreset, floorHeightMeters, roofHeightRatio,
				minimumRoofHeightMeters, maximumRoofHeightMeters, minimumBodyHeightMeters,
				minimumPitchedBuildingHeightMeters, maximumPitchedBuildingHeightMeters,
				value, previewSampleSize, variantSeed, footprintThresholds);
	}

	public ModelingConfig withPreviewSampleSize(int value) {
		return new ModelingConfig(ruleVersion, roofMode, stylePreset, floorHeightMeters, roofHeightRatio,
				minimumRoofHeightMeters, maximumRoofHeightMeters, minimumBodyHeightMeters,
				minimumPitchedBuildingHeightMeters, maximumPitchedBuildingHeightMeters,
				lod, value, variantSeed, footprintThresholds);
	}

	public ModelingConfig withRoofMode(RoofMode value) {
		return new ModelingConfig(ruleVersion, value, stylePreset, floorHeightMeters, roofHeightRatio,
				minimumRoofHeightMeters, maximumRoofHeightMeters, minimumBodyHeightMeters,
				minimumPitchedBuildingHeightMeters, maximumPitchedBuildingHeightMeters,
				lod, previewSampleSize, variantSeed, footprintThresholds);
	}

	public ModelingConfig withStylePreset(StylePresetId value) {
		return new ModelingConfig(ruleVersion, roofMode, value, floorHeightMeters, roofHeightRatio,
				minimumRoofHeightMeters, maximumRoofHeightMeters, minimumBodyHeightMeters,
				minimumPitchedBuildingHeightMeters, maximumPitchedBuildingHeightMeters,
				lod, previewSampleSize, variantSeed, footprintThresholds);
	}

	private static void range(double value, double minimum, double maximum, String name) {
		if (!Double.isFinite(value) || value < minimum || value > maximum) {
			throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
		}
	}
}
