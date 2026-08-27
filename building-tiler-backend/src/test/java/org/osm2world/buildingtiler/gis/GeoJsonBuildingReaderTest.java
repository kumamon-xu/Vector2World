package org.osm2world.buildingtiler.gis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

class GeoJsonBuildingReaderTest {

	@Test
	void readsPolygonHolesMultiPolygonsAndElevation() throws Exception {
		Path input = Path.of(getClass().getResource("/m0-polygons.geojson").toURI());

		DatasetReadResult result = new GeoJsonBuildingReader().read(input, "Elevation");

		assertEquals(4, result.metadata().featureCount());
		assertEquals("UTF-8", result.metadata().sourceEncoding());
		assertEquals(3, result.metadata().validBuildings());
		assertEquals(1, result.metadata().skippedInvalidHeight());
		assertEquals(0, result.metadata().skippedInvalidGeometry());
		assertEquals(12, result.metadata().minHeightMeters());
		assertEquals(48, result.metadata().maxHeightMeters());
		assertEquals(116.6000, result.metadata().boundsWgs84().getMinX(), 1e-12);
		assertEquals(116.6510, result.metadata().boundsWgs84().getMaxX(), 1e-12);
		assertEquals(39.9000, result.metadata().boundsWgs84().getMinY(), 1e-12);
		assertEquals(39.9010, result.metadata().boundsWgs84().getMaxY(), 1e-12);
		assertEquals(3L, result.metadata().geometryTypes().get("Polygon"));
		assertEquals(1L, result.metadata().geometryTypes().get("MultiPolygon"));
		Polygon polygon = assertInstanceOf(Polygon.class, result.buildings().get(0).geometryWgs84());
		assertEquals(1, polygon.getNumInteriorRing());
		MultiPolygon multiPolygon = assertInstanceOf(MultiPolygon.class,
				result.buildings().get(1).geometryWgs84());
		assertEquals(2, multiPolygon.getNumGeometries());
	}

}
