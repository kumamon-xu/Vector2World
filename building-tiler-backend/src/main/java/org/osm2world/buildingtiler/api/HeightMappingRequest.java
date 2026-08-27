package org.osm2world.buildingtiler.api;

public record HeightMappingRequest(
		String fieldName,
		String unit,
		String invalidPolicy,
		Double maximumHeightMeters) {
}
