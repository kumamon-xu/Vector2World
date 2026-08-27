package org.osm2world.buildingtiler.gis;

import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.locationtech.jts.geom.Geometry;
import org.osm2world.buildingtiler.domain.BuildingPartId;

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
		properties = properties == null ? Map.of()
				: Collections.unmodifiableMap(new LinkedHashMap<>(properties));
		parts = parts == null ? List.of() : List.copyOf(parts);
	}
}
