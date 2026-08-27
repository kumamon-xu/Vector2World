package org.osm2world.buildingtiler.gis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;

class UserDatasetParityTest {

	@Test
	void suppliedShapefileAndGeoJsonDescribeTheSameBuildings() throws Exception {
		Path root = workspaceRoot();
		Path geojson = root.resolve("test/geojson/建筑面.geojson");
		Path shapefile = root.resolve("test/shp/建筑面.shp");
		Assumptions.assumeTrue(Files.isRegularFile(geojson) && Files.isRegularFile(shapefile),
				"User-owned M0 fixtures are not present in this checkout");

		DatasetReadResult json = new GeoJsonBuildingReader().read(geojson, "Elevation");
		DatasetReadResult shp = new ShapefileBuildingReader().read(shapefile, "Elevation");

		assertEquals("GB18030", json.metadata().sourceEncoding());
		assertEquals(7412, json.metadata().featureCount());
		assertEquals(json.metadata().featureCount(), shp.metadata().featureCount());
		assertEquals(json.metadata().validBuildings(), shp.metadata().validBuildings());
		assertEquals(json.metadata().minHeightMeters(), shp.metadata().minHeightMeters(), 1e-9);
		assertEquals(json.metadata().maxHeightMeters(), shp.metadata().maxHeightMeters(), 1e-9);
		assertEnvelopeEquals(json.metadata().boundsWgs84(), shp.metadata().boundsWgs84(), 1e-7);
		assertTrue(json.metadata().geometryTypes().containsKey("Polygon"));
	}

	private static Path workspaceRoot() {
		String configured = System.getProperty("maven.multiModuleProjectDirectory");
		if (configured != null) return Path.of(configured).toAbsolutePath().normalize();
		return Path.of("..").toAbsolutePath().normalize();
	}

	private static void assertEnvelopeEquals(Envelope expected, Envelope actual, double tolerance) {
		assertEquals(expected.getMinX(), actual.getMinX(), tolerance);
		assertEquals(expected.getMinY(), actual.getMinY(), tolerance);
		assertEquals(expected.getMaxX(), actual.getMaxX(), tolerance);
		assertEquals(expected.getMaxY(), actual.getMaxY(), tolerance);
	}

}
