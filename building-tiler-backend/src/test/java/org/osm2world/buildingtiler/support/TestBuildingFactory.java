package org.osm2world.buildingtiler.support;

import java.util.Map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.osm2world.buildingtiler.domain.BuildingFeature;

public final class TestBuildingFactory {

	private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

	private TestBuildingFactory() {}

	public static BuildingFeature rectangle(String id, double longitude, double latitude,
			double widthDegrees, double depthDegrees, double heightMeters) {
		return rectangle(id, longitude, latitude, widthDegrees, depthDegrees, heightMeters, Map.of());
	}

	public static BuildingFeature rectangle(String id, double longitude, double latitude,
			double widthDegrees, double depthDegrees, double heightMeters, Map<String, Object> attributes) {
		return new BuildingFeature(id, polygon(new Coordinate[] {
				new Coordinate(longitude, latitude),
				new Coordinate(longitude + widthDegrees, latitude),
				new Coordinate(longitude + widthDegrees, latitude + depthDegrees),
				new Coordinate(longitude, latitude + depthDegrees),
				new Coordinate(longitude, latitude)
		}), heightMeters, attributes);
	}

	public static Polygon polygon(Coordinate[] coordinates) {
		return GEOMETRY_FACTORY.createPolygon(coordinates);
	}

	public static GeometryFactory geometryFactory() {
		return GEOMETRY_FACTORY;
	}
}
