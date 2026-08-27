package org.osm2world.buildingtiler.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ModelingConfigTest {

	@Test
	void exposesVersionedValidatedDefaults() {
		ModelingConfig config = ModelingConfig.defaults();
		assertEquals("m2-rules-v1", config.ruleVersion().value());
		assertEquals(RoofMode.AUTO_SIMPLE, config.roofMode());
		assertEquals(StylePresetId.NEUTRAL_CITY, config.stylePreset());
		assertEquals(3.2, config.floorHeightMeters());
		assertEquals(4, config.lod());
		assertEquals(100, config.previewSampleSize());
	}

	@Test
	void rejectsUnsafeRangesAndUnknownRuleVersions() {
		ModelingConfig defaults = ModelingConfig.defaults();
		assertThrows(IllegalArgumentException.class, () -> defaults.withLod(5));
		assertThrows(IllegalArgumentException.class, () -> defaults.withPreviewSampleSize(49));
		assertThrows(IllegalArgumentException.class, () -> new ModelingConfig(
				new RuleVersion("m2-rules-v2"), defaults.roofMode(), defaults.stylePreset(),
				defaults.floorHeightMeters(), defaults.roofHeightRatio(), defaults.minimumRoofHeightMeters(),
				defaults.maximumRoofHeightMeters(), defaults.minimumBodyHeightMeters(),
				defaults.minimumPitchedBuildingHeightMeters(), defaults.maximumPitchedBuildingHeightMeters(),
				defaults.lod(), defaults.previewSampleSize(), defaults.variantSeed(), defaults.footprintThresholds()));
	}

	@Test
	void parsesAllPublishedPresetIdentifiers() {
		assertEquals(StylePresetId.NEUTRAL_CITY, StylePresetId.parse("neutral-city"));
		assertEquals(StylePresetId.WARM_RESIDENTIAL, StylePresetId.parse("warm_residential"));
		assertEquals(StylePresetId.MODERN_CITY, StylePresetId.parse("modern-city"));
		assertEquals(StylePresetId.INDUSTRIAL, StylePresetId.parse("industrial"));
	}
}
