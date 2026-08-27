package org.osm2world.buildingtiler.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Milestone0PipelineTest {

	@TempDir Path tempDirectory;

	@Test
	void producesAValidatedTwoTileTilesetFromPolygonAndMultiPolygon() throws Exception {
		Path input = Path.of(getClass().getResource("/m0-polygons.geojson").toURI());
		Path output = tempDirectory.resolve("result");

		var result = new Milestone0Pipeline().run(input, output, "Elevation", 15, 4, 2);

		assertEquals(2, result.generation().tileCount());
		assertEquals(2, result.generation().modeledBuildings());
		assertTrue(result.generation().meshCount() > 0);
		assertTrue(result.generation().validation().valid(),
				() -> result.generation().validation().errors().toString());
		assertEquals("1.1", result.generation().validation().assetVersion());
		assertTrue(Files.isRegularFile(output.resolve("tileset.json")));
		assertTrue(Files.isRegularFile(output.resolve("manifest.json")));
		assertTrue(Files.isRegularFile(output.resolve("generation-report.json")));
	}

}
