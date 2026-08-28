package org.osm2world.buildingtiler.tiles;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osm2world.math.geo.LatLon;
import org.osm2world.math.geo.WGS84Util;

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

	@Test
	void acceptsDecodedVerticesAfterGltfAxisAndEcefTransforms() throws Exception {
		writeTriangleGlb(tempDirectory.resolve("triangle.glb"), Mutation.NONE);
		double longitude = Math.toRadians(116.6);
		double latitude = Math.toRadians(39.9);
		double[] transform = WGS84Util.eastNorthUpToEcefMatrix(new LatLon(39.9, 116.6), 0);
		writeTileset("triangle.glb",
				"[%.12f, %.12f, %.12f, %.12f, -1, 2]".formatted(
						longitude - 1e-5, latitude - 1e-5, longitude + 1e-5, latitude + 1e-5),
				transform);

		var result = new TilesetValidator().validate(tempDirectory, 1);

		assertTrue(result.valid(), result.errors().toString());
		assertEquals(3, result.vertexCount());
		assertEquals(1, result.triangleCount());
		assertEquals(GlbSemanticValidator.PROFILE, result.validationProfile());
	}

	@Test
	void rejectsAccessorOverflowTruncatedBinAndOutOfRangeIndices() throws Exception {
		for (Mutation mutation : new Mutation[] {
				Mutation.ACCESSOR_OVERFLOW, Mutation.TRUNCATED_BIN, Mutation.INDEX_OUT_OF_RANGE}) {
			Path directory = Files.createDirectory(tempDirectory.resolve(mutation.name()));
			writeTriangleGlb(directory.resolve("triangle.glb"), mutation);
			writeTileset(directory, "triangle.glb", "[0, 0, 0, 0, 0, 0]", null);
			var result = new TilesetValidator().validate(directory);
			assertFalse(result.valid(), mutation.name());
		}
	}

	@Test
	void rejectsEmptyPrimitiveMissingTextureAndIllegalExtension() throws Exception {
		for (Mutation mutation : new Mutation[] {
				Mutation.EMPTY_PRIMITIVE, Mutation.MISSING_TEXTURE, Mutation.ILLEGAL_EXTENSION}) {
			Path directory = Files.createDirectory(tempDirectory.resolve(mutation.name()));
			writeTriangleGlb(directory.resolve("triangle.glb"), mutation);
			writeTileset(directory, "triangle.glb", "[0, 0, 0, 0, 0, 0]", null);
			var result = new TilesetValidator().validate(directory);
			assertFalse(result.valid(), mutation.name());
		}
	}

	@Test
	void rejectsFinalVerticesWhenTilesetTransformPlacesThemOutsideRegion() throws Exception {
		writeTriangleGlb(tempDirectory.resolve("triangle.glb"), Mutation.NONE);
		writeTileset("triangle.glb", "[2.03, 0.69, 2.04, 0.70, -1, 10]",
				GlbSemanticValidator.identity());

		var result = new TilesetValidator().validate(tempDirectory);

		assertFalse(result.valid());
		assertTrue(result.errors().stream().anyMatch(message -> message.contains("outside boundingVolume.region")));
	}

	@Test
	void reconcilesRegionAgainstFinalTransformedVertices() throws Exception {
		Path glb = tempDirectory.resolve("triangle.glb");
		Path tileset = tempDirectory.resolve("tileset.json");
		writeTriangleGlb(glb, Mutation.NONE);
		double longitude = Math.toRadians(116.6);
		double latitude = Math.toRadians(39.9);
		double[] transform = WGS84Util.eastNorthUpToEcefMatrix(new LatLon(39.9, 116.6), 0);
		writeTileset("triangle.glb", "[%.12f, %.12f, %.12f, %.12f, 0, 0]".formatted(
				longitude, latitude, longitude, latitude), transform);

		new TilesetRegionReconciler().expandToFinalVertices(tileset, glb);
		var result = new TilesetValidator().validate(tempDirectory, 1);

		assertTrue(result.valid(), result.errors().toString());
	}

	private void writeTileset(String content, String region) throws Exception {
		writeTileset(tempDirectory, content, region, null);
	}

	private void writeTileset(String content, String region, double[] transform) throws Exception {
		writeTileset(tempDirectory, content, region, transform);
	}

	private static void writeTileset(Path directory, String content, String region, double[] transform)
			throws Exception {
		String contentJson = content == null ? "" : ", \"content\": {\"uri\": \"" + content + "\"}";
		String transformJson = transform == null ? "" : ", \"transform\": " + Arrays.toString(transform);
		Files.writeString(directory.resolve("tileset.json"), """
				{
				  "asset": {"version": "1.1"},
				  "root": {
				    "boundingVolume": {"region": %s},
				    "geometricError": 0%s%s
				  }
				}
				""".formatted(region, transformJson, contentJson), UTF_8);
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

	private static void writeTriangleGlb(Path file, Mutation mutation) throws Exception {
		ByteBuffer binary = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
		float[][] positions = {{0, 0, 0}, {1, 0, 0}, {0, 1, 0}};
		for (float[] position : positions) for (float component : position) binary.putFloat(component);
		binary.putShort((short)0).putShort((short)1)
				.putShort((short)(mutation == Mutation.INDEX_OUT_OF_RANGE ? 9 : 2));
		while (binary.hasRemaining()) binary.put((byte)0);

		int positionCount = mutation == Mutation.ACCESSOR_OVERFLOW ? 4 : 3;
		String primitive = mutation == Mutation.EMPTY_PRIMITIVE ? "{}" : """
				{"attributes":{"POSITION":0},"indices":1,"mode":4%s}
				""".formatted(mutation == Mutation.MISSING_TEXTURE ? ",\"material\":0" : "").trim();
		String extension = mutation == Mutation.ILLEGAL_EXTENSION
				? ",\"extensionsUsed\":[\"EXT_not_supported\"]" : "";
		String resources = mutation == Mutation.MISSING_TEXTURE ? """
				,"images":[{"uri":"missing.png"}],"textures":[{"source":0}],
				"materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0}}}]
				""".replace("\n", "") : "";
		String json = """
				{"asset":{"version":"2.0"}%s,"buffers":[{"byteLength":44}],
				"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36,"target":34962},
				{"buffer":0,"byteOffset":36,"byteLength":6,"target":34963}],
				"accessors":[{"bufferView":0,"componentType":5126,"count":%d,"type":"VEC3"},
				{"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}],
				"meshes":[{"primitives":[%s]}],"nodes":[{"mesh":0}],
				"scenes":[{"nodes":[0]}],"scene":0%s}
				""".formatted(extension, positionCount, primitive, resources).replace("\n", "");
		byte[] jsonBytes = padded(json.getBytes(UTF_8), (byte)' ');
		byte[] binBytes = mutation == Mutation.TRUNCATED_BIN
				? Arrays.copyOf(binary.array(), 40) : binary.array();
		int declaredBinLength = 44;
		ByteBuffer glb = ByteBuffer.allocate(12 + 8 + jsonBytes.length + 8 + binBytes.length)
				.order(ByteOrder.LITTLE_ENDIAN);
		glb.putInt(0x46546c67).putInt(2).putInt(glb.capacity());
		glb.putInt(jsonBytes.length).putInt(0x4e4f534a).put(jsonBytes);
		glb.putInt(declaredBinLength).putInt(0x004e4942).put(binBytes);
		Files.write(file, glb.array());
	}

	private static byte[] padded(byte[] source, byte padding) {
		int length = (source.length + 3) & ~3;
		byte[] result = Arrays.copyOf(source, length);
		Arrays.fill(result, source.length, length, padding);
		return result;
	}

	private enum Mutation {
		NONE, ACCESSOR_OVERFLOW, TRUNCATED_BIN, INDEX_OUT_OF_RANGE,
		EMPTY_PRIMITIVE, MISSING_TEXTURE, ILLEGAL_EXTENSION
	}

}
