package org.osm2world.buildingtiler.gis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

class CrsSupportTest {

	private static final GeometryFactory FACTORY = new GeometryFactory();

	@Test
	void transformsKnownWebMercatorControlPointWithLongitudeFirstAxisOrder() throws Exception {
		var point = FACTORY.createPoint(new Coordinate(12957588.728337044, 4851421.175183357));
		var wgs84 = CrsSupport.toWgs84(point, CrsSupport.resolve("EPSG:3857", null));
		assertEquals(116.4, wgs84.getCoordinate().x, 1e-7);
		assertEquals(39.9, wgs84.getCoordinate().y, 1e-7);
	}

	@Test
	void roundTripsUtmZone50AndCgcs2000ControlPoints() throws Exception {
		assertRoundTrip("EPSG:32650", 116.4, 39.9, 1e-7);
		assertRoundTrip("EPSG:4490", 116.4, 39.9, 1e-9);
	}

	private static void assertRoundTrip(String projectedCode, double longitude, double latitude,
			double tolerance) throws Exception {
		var wgs84 = CRS.decode("EPSG:4326", true);
		var projected = CRS.decode(projectedCode, true);
		var point = FACTORY.createPoint(new Coordinate(longitude, latitude));
		var sourcePoint = JTS.transform(point, CRS.findMathTransform(wgs84, projected, true));
		var restored = CrsSupport.toWgs84(sourcePoint, CrsSupport.resolve(projectedCode, null));
		assertEquals(longitude, restored.getCoordinate().x, tolerance);
		assertEquals(latitude, restored.getCoordinate().y, tolerance);
	}
}
