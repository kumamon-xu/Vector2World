package org.osm2world.buildingtiler.domain;

public record StyledBuilding(BuildingFeature feature, FootprintMetrics footprint, BuildingStyle style) {
	public StyledBuilding {
		if (feature == null || footprint == null || style == null) {
			throw new IllegalArgumentException("Feature, footprint metrics and style are required");
		}
		if (Double.compare(feature.heightMeters(), style.heightMeters()) != 0) {
			throw new IllegalArgumentException("Procedural style must preserve the source height exactly");
		}
	}
}
