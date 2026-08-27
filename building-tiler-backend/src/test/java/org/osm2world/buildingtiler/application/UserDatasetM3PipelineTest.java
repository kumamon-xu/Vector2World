package org.osm2world.buildingtiler.application;

	import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

	import java.io.ByteArrayInputStream;
	import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
	import java.util.Locale;
	import java.util.zip.ZipEntry;
	import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.domain.HeightUnit;
import org.osm2world.buildingtiler.domain.InvalidHeightPolicy;
import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.domain.TilingConfig;
import org.osm2world.buildingtiler.gis.GeoJsonBuildingReader;
import org.osm2world.buildingtiler.gis.ImportOptions;
import org.osm2world.buildingtiler.gis.ShapefileBuildingReader;
import org.osm2world.buildingtiler.gis.UploadLimits;
import org.osm2world.buildingtiler.modeling.BuildingRuleEngine;
import org.osm2world.buildingtiler.modeling.OsmTagMapper;
import org.osm2world.buildingtiler.osm2world.Osm2WorldEngineAdapter;
import org.osm2world.buildingtiler.tiles.Osm2WorldTileRenderer;
import org.osm2world.buildingtiler.tiles.TileOwnershipPlanner;
import org.osm2world.buildingtiler.tiles.TilesetTreeAssembler;
import org.osm2world.buildingtiler.tiles.TilesetValidator;

import com.google.gson.JsonParser;

class UserDatasetM3PipelineTest {

	@TempDir Path temporary;

	@Test
	void suppliedFormatsHaveIdenticalZ15OwnershipAndBothCompleteTheFullPipeline() throws Exception {
		Path root = workspaceRoot();
		Path geojson = root.resolve("test/geojson/建筑面.geojson");
		Path shapefile = root.resolve("test/shp/建筑面.shp");
		Assumptions.assumeTrue(Files.isRegularFile(geojson) && Files.isRegularFile(shapefile),
				"User-owned M3 fixtures are not present in this checkout");

		var json = new GeoJsonBuildingReader().read(geojson, "Elevation");
		var shp = new ShapefileBuildingReader().read(shapefile, "Elevation");
		var planner = new TileOwnershipPlanner();
		var jsonPlan = planner.plan(json.buildings(), 15, 4);
		var shpPlan = planner.plan(shp.buildings(), 15, 4);
		assertEquals(7412, json.buildings().size());
		assertEquals(97, jsonPlan.tiles().size());
		assertEquals(jsonPlan.tiles().stream().map(value -> value.tile().toString()).toList(),
				shpPlan.tiles().stream().map(value -> value.tile().toString()).toList());
		assertEquals(jsonPlan.ownershipHash(), shpPlan.ownershipHash());
		assertTrue(jsonPlan.crossTileBuildings() > 0);

		DatasetService datasets = new DatasetService(temporary.resolve("datasets"), UploadLimits.defaults());
		String datasetId;
		try (var stream = Files.newInputStream(geojson)) {
			datasetId = datasets.upload("建筑面.geojson", "application/geo+json", Files.size(geojson), stream,
					ImportOptions.defaults()).id().toString();
		}
		byte[] shapefileZip = zipShapefile(shapefile);
		String shapefileDatasetId = datasets.upload("建筑面.zip", "application/zip", shapefileZip.length,
				new ByteArrayInputStream(shapefileZip), ImportOptions.defaults()).id().toString();
		int workers = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
		var renderer = new Osm2WorldTileRenderer(
				new Osm2WorldEngineAdapter(new OsmTagMapper(), new BuildingRuleEngine()));
		try (var jobs = new GenerationJobService(temporary.resolve("jobs"), Duration.ofHours(1), workers, 128,
				datasets, planner, renderer, new TilesetTreeAssembler(), new TilesetValidator())) {
			var heightMapping = new HeightMapping("Elevation", HeightUnit.M, InvalidHeightPolicy.SKIP, 10_000);
			var spec = new GenerationJobSpec(datasetId,
					heightMapping,
					ModelingConfig.defaults().withLod(2), TilingConfig.defaults(workers, 128));
			ManagedGenerationJob job = jobs.create(spec);
			waitFor(() -> job.state().terminal(), Duration.ofMinutes(5));
			assertComplete(job, jsonPlan.ownershipHash());

			ManagedGenerationJob shapefileJob = jobs.create(new GenerationJobSpec(shapefileDatasetId,
					heightMapping, ModelingConfig.defaults().withLod(2), TilingConfig.defaults(workers, 128)));
			waitFor(() -> shapefileJob.state().terminal(), Duration.ofMinutes(5));
			assertComplete(shapefileJob, jsonPlan.ownershipHash());

			Path result = job.result().resultDirectory();
			var manifest = JsonParser.parseReader(Files.newBufferedReader(result.resolve("manifest.json")))
					.getAsJsonObject();
			var report = JsonParser.parseReader(Files.newBufferedReader(result.resolve("generation-report.json")))
					.getAsJsonObject();
			assertEquals(97, manifest.getAsJsonArray("tileContents").size());
			assertEquals(15, manifest.get("zoom").getAsInt());
			assertEquals(2, manifest.getAsJsonArray("lods").get(0).getAsInt());
			assertEquals("3DTILES", manifest.getAsJsonArray("outputFormats").get(0).getAsString());
			assertEquals(97, report.get("successfulTileContents").getAsInt());
			assertEquals(7412, report.get("modeledBuildings").getAsInt());
			assertEquals(directoryBytes(result), report.get("outputBytes").getAsLong());
			var shapefileManifest = JsonParser.parseReader(Files.newBufferedReader(
					shapefileJob.result().resultDirectory().resolve("manifest.json"))).getAsJsonObject();
			assertEquals("SHP", shapefileManifest.get("sourceFormat").getAsString());
		}
	}

	private static void assertComplete(ManagedGenerationJob job, String ownershipHash) {
		assertTrue(job.state() == GenerationJobState.COMPLETED
				|| job.state() == GenerationJobState.COMPLETED_WITH_WARNINGS, job.error());
		assertEquals(97, job.result().plannedTiles());
		assertEquals(97, job.result().successfulTiles());
		assertEquals(0, job.result().failedTiles());
		assertEquals(7412, job.result().modeledBuildings());
		assertEquals(ownershipHash, job.result().ownershipHash());
		assertTrue(job.result().validation().valid());
		assertEquals(97, job.result().validation().glbCount());
		assertFalse(job.result().artifacts().isEmpty());
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

	private static long directoryBytes(Path root) throws Exception {
		try (var files = Files.walk(root)) {
			return files.filter(Files::isRegularFile).mapToLong(file -> {
				try { return Files.size(file); }
				catch (Exception exception) { throw new IllegalStateException(exception); }
			}).sum();
		}
	}

	private static void waitFor(java.util.function.BooleanSupplier condition, Duration timeout) throws Exception {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for full M3 pipeline");
			Thread.sleep(50);
		}
	}

	private static Path workspaceRoot() {
		String configured = System.getProperty("maven.multiModuleProjectDirectory");
		if (configured != null) return Path.of(configured).toAbsolutePath().normalize();
		return Path.of("..").toAbsolutePath().normalize();
	}
}
