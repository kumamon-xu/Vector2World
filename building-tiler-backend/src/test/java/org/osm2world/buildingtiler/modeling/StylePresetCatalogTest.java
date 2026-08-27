package org.osm2world.buildingtiler.modeling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.osm2world.buildingtiler.domain.StylePresetId;

class StylePresetCatalogTest {

	@Test
	void loadsAllFourPackagedControlledPresets() {
		StylePresetCatalog catalog = new StylePresetCatalog();
		assertEquals(4, catalog.all().size());
		for (StylePresetId id : StylePresetId.values()) {
			StylePreset preset = catalog.get(id);
			assertFalse(preset.wallMaterials().isEmpty());
			assertFalse(preset.roofMaterials().isEmpty());
			assertTrue(preset.wallColors().stream().allMatch(value -> value.matches("#[0-9a-fA-F]{6}")));
			assertTrue(preset.roofColors().stream().allMatch(value -> value.matches("#[0-9a-fA-F]{6}")));
		}
	}
}
