package org.osm2world.buildingtiler.modeling;

import java.util.List;

import org.osm2world.buildingtiler.domain.StylePresetId;

public record StylePreset(
		StylePresetId id,
		String version,
		List<String> wallMaterials,
		List<String> roofMaterials,
		List<String> wallColors,
		List<String> roofColors,
		boolean windows) {

	public StylePreset {
		wallMaterials = List.copyOf(wallMaterials);
		roofMaterials = List.copyOf(roofMaterials);
		wallColors = List.copyOf(wallColors);
		roofColors = List.copyOf(roofColors);
		if (id == null || version == null || version.isBlank() || wallMaterials.isEmpty()
				|| roofMaterials.isEmpty() || wallColors.isEmpty() || roofColors.isEmpty()) {
			throw new IllegalArgumentException("Style preset is incomplete");
		}
	}
}
