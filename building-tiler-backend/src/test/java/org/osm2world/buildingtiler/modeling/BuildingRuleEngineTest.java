package org.osm2world.buildingtiler.modeling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.domain.RoofMode;
import org.osm2world.buildingtiler.domain.StylePresetId;
import org.osm2world.buildingtiler.support.TestBuildingFactory;

class BuildingRuleEngineTest {

	private final BuildingRuleEngine rules = new BuildingRuleEngine();

	@Test
	void conservativeIsAlwaysFlatAndPreservesExactHeight() {
		var feature = TestBuildingFactory.rectangle("a", 116.6, 39.9, 0.001, 0.0005, 21.123456);
		var style = rules.evaluate(feature, ModelingConfig.defaults().withRoofMode(RoofMode.CONSERVATIVE)).style();
		assertEquals(21.123456, style.heightMeters());
		assertEquals("flat", style.roofShape());
		assertEquals(0, style.roofHeightMeters());
		assertEquals(7, style.levels());
	}

	@Test
	void autoSimpleUsesOnlyVerifiedRoofsAndFallsBackForLowOrIrregularBuildings() {
		var simple = TestBuildingFactory.rectangle("simple", 116.6, 39.9, 0.001, 0.0005, 18);
		var low = TestBuildingFactory.rectangle("low", 116.6, 39.9, 0.001, 0.0005, 4);
		var auto = ModelingConfig.defaults();
		assertTrue(java.util.Set.of("gabled", "hipped").contains(rules.evaluate(simple, auto).style().roofShape()));
		assertEquals("flat", rules.evaluate(low, auto).style().roofShape());
	}

	@Test
	void roofHeightIsBoundedAndCannotConsumeTheBody() {
		var feature = TestBuildingFactory.rectangle("roof", 116.6, 39.9, 0.001, 0.0005, 7,
				Map.of("roof:shape", "gabled", "roof:height", 100));
		var style = rules.evaluate(feature, ModelingConfig.defaults()).style();
		assertEquals("gabled", style.roofShape());
		assertEquals(3.0, style.roofHeightMeters());
		assertTrue(style.heightMeters() - style.roofHeightMeters() >= 2.5);
	}

	@Test
	void flatFacadeDetailEnablesWindowsWhileIndustrialPresetCanDisableThem() {
		var feature = TestBuildingFactory.rectangle("facade", 116.6, 39.9, 0.001, 0.0005, 18);
		var facade = rules.evaluate(feature,
				ModelingConfig.defaults().withRoofMode(RoofMode.FLAT_FACADE_DETAIL)
						.withStylePreset(StylePresetId.INDUSTRIAL)).style();
		var industrial = rules.evaluate(feature,
				ModelingConfig.defaults().withRoofMode(RoofMode.CONSERVATIVE)
						.withStylePreset(StylePresetId.INDUSTRIAL)).style();
		assertEquals("flat", facade.roofShape());
		assertTrue(facade.windows());
		assertEquals(false, industrial.windows());
	}

	@Test
	void validSourceAppearanceWinsOverPresetButNeverOverridesHeight() {
		var feature = TestBuildingFactory.rectangle("source", 116.6, 39.9, 0.001, 0.0005, 18,
				Map.of("height", 999, "roof:shape", "hipped", "building:material", "wood",
						"building:colour", "#123456"));
		var style = rules.evaluate(feature, ModelingConfig.defaults()).style();
		assertEquals(18, style.heightMeters());
		assertEquals("hipped", style.roofShape());
		assertEquals("wood", style.wallMaterial());
		assertEquals("#123456", style.wallColor());
		assertEquals("SOURCE", style.provenance().get("building:material"));
	}
}
