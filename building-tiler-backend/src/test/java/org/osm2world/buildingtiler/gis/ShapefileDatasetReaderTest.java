package org.osm2world.buildingtiler.gis;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.Charset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Polygon;
import org.osm2world.buildingtiler.support.TestShapefileFactory;

class ShapefileDatasetReaderTest {

	@TempDir Path temporaryDirectory;

	@Test
	void readsRequiredSidecarsCpgFieldsCrsAndPolygonHoles() throws Exception {
		Path shp = TestShapefileFactory.create(temporaryDirectory.resolve("complete"), "buildings", UTF_8);
		DatasetInspection inspection = new ShapefileDatasetReader().inspect(shp, ImportOptions.defaults());
		assertEquals("SHP", inspection.format());
		assertEquals("UTF-8", inspection.sourceEncoding());
		assertEquals(1, inspection.featureCount());
		assertEquals(1, inspection.features().size());
		assertEquals(1, ((Polygon) inspection.features().get(0).geometryWgs84()).getNumInteriorRing());
		assertEquals("Elevation", inspection.heightCandidates().get(0).fieldName());
		assertEquals("测试建筑", inspection.fields().stream().filter(field -> field.name().equals("NAME"))
				.findFirst().orElseThrow().sampleValues().get(0));
	}

	@Test
	void missingPrjRequiresExplicitCrsAndMissingDbfIsActionable() throws Exception {
		Path shp = TestShapefileFactory.create(temporaryDirectory.resolve("missing"), "buildings", UTF_8);
		Files.delete(temporaryDirectory.resolve("missing/buildings.prj"));
		DatasetImportException crs = assertThrows(DatasetImportException.class,
				() -> new ShapefileDatasetReader().inspect(shp, ImportOptions.defaults()));
		assertEquals(DatasetErrorCode.CRS_REQUIRED, crs.code());
		DatasetInspection overridden = new ShapefileDatasetReader().inspect(shp,
				ImportOptions.defaults().withExplicitCrs("EPSG:4326"));
		assertEquals(1, overridden.features().size());

		Files.delete(temporaryDirectory.resolve("missing/buildings.dbf"));
		DatasetImportException sidecar = assertThrows(DatasetImportException.class,
				() -> new ShapefileDatasetReader().inspect(shp,
						ImportOptions.defaults().withExplicitCrs("EPSG:4326")));
		assertEquals(DatasetErrorCode.SHAPEFILE_SIDECAR_MISSING, sidecar.code());
	}

	@Test
	void readsGbkDbfWhenCpgDeclaresEncoding() throws Exception {
		Path shp = TestShapefileFactory.create(temporaryDirectory.resolve("gbk"), "buildings", Charset.forName("GBK"));
		DatasetInspection inspection = new ShapefileDatasetReader().inspect(shp, ImportOptions.defaults());
		assertEquals("GBK", inspection.sourceEncoding());
		assertEquals("测试建筑", inspection.fields().stream().filter(field -> field.name().equals("NAME"))
				.findFirst().orElseThrow().sampleValues().get(0));
	}
}
