package org.osm2world.buildingtiler.osm2world;

public enum ModelFailureCategory {
	VALIDATION,
	GEOMETRY,
	TAG_MAPPING,
	OSM2WORLD_CONVERSION,
	GLTF_EXPORT,
	EMPTY_INPUT
}
