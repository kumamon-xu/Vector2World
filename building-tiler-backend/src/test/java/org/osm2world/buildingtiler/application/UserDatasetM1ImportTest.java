package org.osm2world.buildingtiler.application;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.domain.HeightUnit;
import org.osm2world.buildingtiler.gis.GeoJsonDatasetReader;
import org.osm2world.buildingtiler.gis.ImportOptions;
import org.osm2world.buildingtiler.gis.UploadLimits;

class UserDatasetM1ImportTest {

	@TempDir Path temporaryDirectory;

	@Test
	void importsSuppliedLegacyEncodedZipWithoutRepacking() throws Exception {
		Path zip = workspaceRoot().resolve("test/shp/建筑面.zip");
		Assumptions.assumeTrue(Files.isRegularFile(zip),
				"User-owned legacy ZIP fixture is not present in this checkout");

		DatasetService service = new DatasetService(temporaryDirectory.resolve("legacy-datasets"),
				UploadLimits.defaults());
		ManagedDataset dataset;
		try (var input = Files.newInputStream(zip)) {
			dataset = service.upload("建筑面.zip", "application/zip", Files.size(zip), input,
					ImportOptions.defaults());
		}

		assertEquals(7412, dataset.inspection().featureCount());
		assertEquals("GB18030", dataset.inspection().archiveEntryEncoding());
		assertEquals(true, dataset.inspection().archiveEntryEncodingFallback());
		assertTrue(dataset.inspection().issues().stream()
				.anyMatch(issue -> issue.code().equals("ZIP_ENTRY_ENCODING_FALLBACK")));
	}

	@Test
	void suppliedGeoJsonAndCompleteShapefileZipPassM1ImportAndMapping() throws Exception {
		Path root = workspaceRoot();
		Path geojson = root.resolve("test/geojson/建筑面.geojson");
		Path shapefile = root.resolve("test/shp/建筑面.shp");
		Assumptions.assumeTrue(Files.isRegularFile(geojson) && Files.isRegularFile(shapefile),
				"User-owned M1 fixtures are not present in this checkout");

		var json = new GeoJsonDatasetReader().inspect(geojson, ImportOptions.defaults());
		byte[] zip = zipShapefile(shapefile);
		DatasetService service = new DatasetService(temporaryDirectory.resolve("datasets"), UploadLimits.defaults());
		ManagedDataset shp = service.upload("建筑面.zip", "application/zip", zip.length,
				new ByteArrayInputStream(zip), ImportOptions.defaults());

		assertEquals(7412, json.featureCount());
		assertEquals(json.featureCount(), shp.inspection().featureCount());
		assertEquals(json.features().size(), shp.inspection().features().size());
		assertEquals("GB18030", json.sourceEncoding());
		assertEquals("Elevation", json.heightCandidates().get(0).fieldName());
		assertEquals(json.boundsWgs84().getMinX(), shp.inspection().boundsWgs84().getMinX(), 1e-7);
		assertEquals(json.boundsWgs84().getMaxY(), shp.inspection().boundsWgs84().getMaxY(), 1e-7);

		var jsonBuildings = json.materialize(new HeightMapping("Elevation", HeightUnit.M));
		var shpBuildings = service.materialize(shp.id().toString(), new HeightMapping("Elevation", HeightUnit.M));
		assertEquals(jsonBuildings.buildings().size(), shpBuildings.buildings().size());
		assertTrue(jsonBuildings.buildings().stream().allMatch(feature -> feature.heightMeters() > 0));
	}

	private static byte[] zipShapefile(Path shapefile) throws Exception {
		String name = shapefile.getFileName().toString();
		String base = name.substring(0, name.lastIndexOf('.')).toLowerCase(Locale.ROOT) + ".";
		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			 ZipOutputStream zip = new ZipOutputStream(bytes, UTF_8);
			 var files = Files.list(shapefile.getParent())) {
			for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
				if (!file.getFileName().toString().toLowerCase(Locale.ROOT).startsWith(base)) continue;
				zip.putNextEntry(new ZipEntry(file.getFileName().toString()));
				Files.copy(file, zip);
				zip.closeEntry();
			}
			zip.finish();
			return bytes.toByteArray();
		}
	}

	private static Path workspaceRoot() {
		String configured = System.getProperty("maven.multiModuleProjectDirectory");
		if (configured != null) return Path.of(configured).toAbsolutePath().normalize();
		return Path.of("..").toAbsolutePath().normalize();
	}
}
