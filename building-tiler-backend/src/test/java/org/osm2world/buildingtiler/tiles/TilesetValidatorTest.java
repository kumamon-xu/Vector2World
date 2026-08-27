package org.osm2world.buildingtiler.tiles;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TilesetValidatorTest {

	@TempDir Path tempDirectory;

	@Test
	void rejectsMissingTileContent() throws Exception {
		Files.writeString(tempDirectory.resolve("tileset.json"), """
				{
				  "asset": {"version": "1.1"},
				  "root": {
				    "boundingVolume": {"region": [2, 0.5, 2.1, 0.6, 0, 50]},
				    "geometricError": 0,
				    "content": {"uri": "missing.glb"}
				  }
				}
				""", UTF_8);

		var result = new TilesetValidator().validate(tempDirectory);

		assertFalse(result.valid());
		assertTrue(result.errors().stream().anyMatch(message -> message.contains("Missing GLB")));
	}

	@Test
	void rejectsContentThatEscapesTheResultDirectoryWithoutThrowing() throws Exception {
		Files.writeString(tempDirectory.resolve("tileset.json"), """
				{
				  "asset": {"version": "1.1"},
				  "root": {
				    "boundingVolume": {"region": [2, 0.5, 2.1, 0.6, 0, 50]},
				    "geometricError": 0,
				    "content": {"uri": "../outside.glb"}
				  }
				}
				""", UTF_8);

		var result = new TilesetValidator().validate(tempDirectory);

		assertFalse(result.valid());
		assertTrue(result.errors().stream().anyMatch(message -> message.contains("escapes result directory")));
	}

	@Test
	void rejectsBadGlbHeader() throws Exception {
		Files.write(tempDirectory.resolve("bad.glb"), new byte[20]);
		writeTileset("bad.glb", "[2, 0.5, 2.1, 0.6, 0, 50]");

		var result = new TilesetValidator().validate(tempDirectory);

		assertFalse(result.valid());
		assertTrue(result.errors().stream().anyMatch(message -> message.contains("Invalid GLB magic")));
	}

	@Test
	void rejectsNonFiniteBounds() throws Exception {
		writeTileset(null, "[2, 0.5, 1e999, 0.6, 0, 50]");

		var result = new TilesetValidator().validate(tempDirectory);

		assertFalse(result.valid());
		assertTrue(result.errors().stream().anyMatch(message -> message.contains("Non-finite")));
	}

	private void writeTileset(String content, String region) throws Exception {
		String contentJson = content == null ? "" : ", \"content\": {\"uri\": \"" + content + "\"}";
		Files.writeString(tempDirectory.resolve("tileset.json"), """
				{
				  "asset": {"version": "1.1"},
				  "root": {
				    "boundingVolume": {"region": %s},
				    "geometricError": 0%s
				  }
				}
				""".formatted(region, contentJson), UTF_8);
	}

}
