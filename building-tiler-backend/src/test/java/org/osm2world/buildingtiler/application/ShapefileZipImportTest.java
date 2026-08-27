package org.osm2world.buildingtiler.application;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.domain.HeightUnit;
import org.osm2world.buildingtiler.gis.DatasetErrorCode;
import org.osm2world.buildingtiler.gis.DatasetImportException;
import org.osm2world.buildingtiler.gis.ImportOptions;
import org.osm2world.buildingtiler.gis.UploadLimits;
import org.osm2world.buildingtiler.support.TestShapefileFactory;

class ShapefileZipImportTest {

	@TempDir Path temporaryDirectory;

	@Test
	void importsCompleteShapefileZipAndReadsCpgAndHeight() throws Exception {
		Path shp = TestShapefileFactory.create(temporaryDirectory.resolve("source"), "buildings", UTF_8);
		byte[] zip = TestShapefileFactory.zip(shp, null);
		DatasetService service = new DatasetService(temporaryDirectory.resolve("datasets"), UploadLimits.defaults());
		ManagedDataset dataset = service.upload("buildings.zip", "application/zip", zip.length,
				new ByteArrayInputStream(zip), ImportOptions.defaults());

		assertEquals("SHP", dataset.inspection().format());
		assertEquals("UTF-8", dataset.inspection().sourceEncoding());
		assertEquals(1, dataset.inspection().features().size());
		var result = service.materialize(dataset.id().toString(), new HeightMapping("Elevation", HeightUnit.CM));
		assertEquals(12.34, result.buildings().get(0).heightMeters(), 1e-12);
	}

	@Test
	void missingPrjInZipFailsUnlessCrsIsExplicit() throws Exception {
		Path shp = TestShapefileFactory.create(temporaryDirectory.resolve("source"), "buildings", UTF_8);
		byte[] zip = TestShapefileFactory.zip(shp, ".prj");
		DatasetService service = new DatasetService(temporaryDirectory.resolve("datasets"), UploadLimits.defaults());
		DatasetImportException missing = assertThrows(DatasetImportException.class,
				() -> service.upload("buildings.zip", "application/zip", zip.length,
						new ByteArrayInputStream(zip), ImportOptions.defaults()));
		assertEquals(DatasetErrorCode.CRS_REQUIRED, missing.code());

		ManagedDataset overridden = service.upload("buildings.zip", "application/zip", zip.length,
				new ByteArrayInputStream(zip), ImportOptions.defaults().withExplicitCrs("EPSG:4326"));
		assertEquals(1, overridden.inspection().features().size());
	}

	@Test
	void missingDbfInZipReturnsActionableSidecarError() throws Exception {
		Path shp = TestShapefileFactory.create(temporaryDirectory.resolve("missing-dbf"), "buildings", UTF_8);
		byte[] zip = TestShapefileFactory.zip(shp, ".dbf");
		DatasetService service = new DatasetService(temporaryDirectory.resolve("datasets-dbf"), UploadLimits.defaults());
		DatasetImportException missing = assertThrows(DatasetImportException.class,
				() -> service.upload("buildings.zip", "application/zip", zip.length,
						new ByteArrayInputStream(zip), ImportOptions.defaults()));
		assertEquals(DatasetErrorCode.SHAPEFILE_SIDECAR_MISSING, missing.code());
	}
}
