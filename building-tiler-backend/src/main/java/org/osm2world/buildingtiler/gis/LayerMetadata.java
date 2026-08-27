package org.osm2world.buildingtiler.gis;

public record LayerMetadata(String name, String geometryType, boolean selected) {
	public LayerMetadata {
		if (name == null || name.isBlank()) throw new IllegalArgumentException("Layer name is required");
	}
}
