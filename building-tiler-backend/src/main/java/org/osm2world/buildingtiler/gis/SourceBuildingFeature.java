package org.osm2world.buildingtiler.gis;

import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Geometry;
import org.osm2world.buildingtiler.domain.BuildingPartId;
import org.osm2world.buildingtiler.domain.ImmutableAttributes;

public record SourceBuildingFeature(
		String id,
		Geometry geometryWgs84,
		Map<String, Object> properties,
		List<BuildingPartId> parts,
		String sourceGeometryType,
		boolean repaired) {

	public SourceBuildingFeature {
		if (id == null || id.isBlank()) throw new IllegalArgumentException("Feature id must not be blank");
		if (geometryWgs84 == null || geometryWgs84.isEmpty()) throw new IllegalArgumentException("Feature geometry is required");
		properties = ImmutableAttributes.copyOf(properties);
		parts = parts == null ? List.of() : List.copyOf(parts);
	}
}
