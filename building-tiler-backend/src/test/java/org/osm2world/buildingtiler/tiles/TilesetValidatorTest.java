package org.osm2world.buildingtiler.tiles;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
	import java.nio.ByteBuffer;
	import java.nio.ByteOrder;

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

	@Test
	void rejectsWindowsSeparatorsAndExpectedContentCountMismatch() throws Exception {
		writeTileset("nested\\\\tile.glb", "[2, 0.5, 2.1, 0.6, 0, 50]");
		var separators = new TilesetValidator().validate(tempDirectory);
		assertFalse(separators.valid());
		assertTrue(separators.errors().stream().anyMatch(message -> message.contains("use '/' separators")));

		writeTileset(null, "[2, 0.5, 2.1, 0.6, 0, 50]");
		var count = new TilesetValidator().validate(tempDirectory, 1);
		assertFalse(count.valid());
		assertTrue(count.errors().stream().anyMatch(message -> message.contains("Expected 1 GLB")));
	}

	@Test
	void rejectsBadGlbVersionAndDeclaredLength() throws Exception {
		writeGlb(tempDirectory.resolve("bad-version.glb"), 1, 999);
		writeTileset("bad-version.glb", "[2, 0.5, 2.1, 0.6, 0, 50]");
		var result = new TilesetValidator().validate(tempDirectory);
		assertFalse(result.valid());
		assertTrue(result.errors().stream().anyMatch(message -> message.contains("GLB version 2")));
		assertTrue(result.errors().stream().anyMatch(message -> message.contains("length mismatch")));
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

	private static void writeGlb(Path file, int version, int declaredLength) throws Exception {
		byte[] json = "{\"asset\":{\"version\":\"2.0\"}} ".getBytes(UTF_8);
		ByteBuffer data = ByteBuffer.allocate(20 + json.length).order(ByteOrder.LITTLE_ENDIAN);
		data.putInt(0x46546c67);
		data.putInt(version);
		data.putInt(declaredLength);
		data.putInt(json.length);
		data.putInt(0x4e4f534a);
		data.put(json);
		Files.write(file, data.array());
	}

}
