package org.osm2world.buildingtiler.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.gis.GeoJsonBuildingReader;
import org.osm2world.buildingtiler.gis.ShapefileBuildingReader;
import org.osm2world.buildingtiler.modeling.BuildingRuleEngine;
import org.osm2world.buildingtiler.modeling.OsmTagMapper;
import org.osm2world.buildingtiler.modeling.RepresentativeSampleSelector;
import org.osm2world.buildingtiler.osm2world.Osm2WorldEngineAdapter;
import org.osm2world.buildingtiler.tiles.ModelPreviewWriterAdapter;
import org.osm2world.buildingtiler.tiles.TilesetValidator;

class UserDatasetM2ModelingTest {

	@TempDir Path temporary;

	@Test
	void suppliedShpAndGeoJsonProduceSameStableStylesAndValidatedRepresentativePreview() throws Exception {
		Path root = workspaceRoot();
		Path geojson = root.resolve("test/geojson/建筑面.geojson");
		Path shapefile = root.resolve("test/shp/建筑面.shp");
		Assumptions.assumeTrue(Files.isRegularFile(geojson) && Files.isRegularFile(shapefile),
				"User-owned M2 fixtures are not present in this checkout");
		var json = new GeoJsonBuildingReader().read(geojson, "Elevation");
		var shp = new ShapefileBuildingReader().read(shapefile, "Elevation");
		ModelingConfig config = ModelingConfig.defaults().withPreviewSampleSize(50).withLod(2);
		RepresentativeSampleSelector selector = new RepresentativeSampleSelector();
		var jsonSample = selector.select(json.buildings(), config);
		var shpSample = selector.select(shp.buildings(), config);
		assertEquals(50, jsonSample.features().size());
		assertEquals(jsonSample.selectionHash(), shpSample.selectionHash());
		BuildingRuleEngine rules = new BuildingRuleEngine();
		List<String> jsonStyles = jsonSample.features().stream().map(value -> rules.evaluate(value, config).style().outputHash())
				.sorted().toList();
		List<String> shpStyles = shpSample.features().stream().map(value -> rules.evaluate(value, config).style().outputHash())
				.sorted().toList();
		assertEquals(jsonStyles, shpStyles);
		var writer = new ModelPreviewWriterAdapter(
				new Osm2WorldEngineAdapter(new OsmTagMapper(), rules), new TilesetValidator());
		var result = writer.write(jsonSample.features(), config, temporary.resolve("preview"),
				jsonSample.bucketCoverage(), jsonSample.selectionHash());
		assertEquals(50, result.modeledBuildings());
		assertTrue(result.validation().valid());
		assertEquals("1.1", result.validation().assetVersion());
		assertTrue(Files.size(temporary.resolve("preview/tileset.glb")) > 0);
	}

	private static Path workspaceRoot() {
		String configured = System.getProperty("maven.multiModuleProjectDirectory");
		if (configured != null) return Path.of(configured).toAbsolutePath().normalize();
		return Path.of("..").toAbsolutePath().normalize();
	}
}
