package org.osm2world.buildingtiler.domain;

import java.util.Map;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

public record BuildingFeature(
		String id,
		Geometry geometryWgs84,
		double heightMeters,
		Map<String, Object> sourceAttributes) {

	public BuildingFeature {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("Building id must not be blank");
		}
		if (!(geometryWgs84 instanceof Polygon || geometryWgs84 instanceof MultiPolygon)
				|| geometryWgs84.isEmpty()) {
			throw new IllegalArgumentException("Building geometry must be a non-empty Polygon or MultiPolygon");
		}
		if (!Double.isFinite(heightMeters) || heightMeters <= 0) {
			throw new IllegalArgumentException("Building height must be a finite positive number of meters");
		}
		sourceAttributes = ImmutableAttributes.copyOf(sourceAttributes);
	}

}
