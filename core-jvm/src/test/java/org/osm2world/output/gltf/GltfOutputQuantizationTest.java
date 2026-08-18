package org.osm2world.output.gltf;

import static org.junit.Assert.*;
import static org.osm2world.output.gltf.GltfOutput.KHR_MESH_QUANTIZATION;
import static org.osm2world.scene.material.Material.Interpolation.FLAT;
import static org.osm2world.util.test.TestFileUtil.createTempFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.osm2world.conversion.O2WConfig;
import org.osm2world.map_data.creation.MapDataBuilder;
import org.osm2world.map_data.data.MapNode;
import org.osm2world.math.VectorXYZ;
import org.osm2world.math.shapes.TriangleXYZ;
import org.osm2world.output.gltf.data.GltfAccessor;
import org.osm2world.scene.Scene;
import org.osm2world.scene.color.Color;
import org.osm2world.scene.material.Material;
import org.osm2world.scene.mesh.Mesh;
import org.osm2world.scene.mesh.TriangleGeometry;
import org.osm2world.util.platform.image.ImageImplementationJvm;
import org.osm2world.util.platform.json.JsonImplementationJvm;
import org.osm2world.util.test.TestWorldModule;

/**
 * tests the use of the KHR_mesh_quantization extension by {@link GltfOutput}
 */
public class GltfOutputQuantizationTest {

	static {
		JsonImplementationJvm.register();
		ImageImplementationJvm.register();
	}

	/** a few triangles spread over an area of 1000 m, i.e. more than a single tile would cover */
	private static final List<TriangleXYZ> TRIANGLES = List.of(
			new TriangleXYZ(new VectorXYZ(0, 0, 0), new VectorXYZ(300, 0, 0), new VectorXYZ(0, 0, 400)),
			new TriangleXYZ(new VectorXYZ(-500, 20, -500), new VectorXYZ(500, 20, -500), new VectorXYZ(-500, 70, 500)));

	@Test
	public void testQuantizationAtLowLod() throws IOException {

		String json = writeGltf(1, KHR_MESH_QUANTIZATION);

		assertTrue("the extension is declared as used: " + json,
				json.contains("\"extensionsUsed\":[\"" + KHR_MESH_QUANTIZATION + "\"]"));
		assertTrue("the extension is declared as required: " + json,
				json.contains("\"extensionsRequired\":[\"" + KHR_MESH_QUANTIZATION + "\"]"));

		/* positions are 16 bit integers, normals are normalized 8 bit integers */

		assertTrue("positions use SHORT components: " + json,
				json.contains("\"componentType\":" + GltfAccessor.TYPE_SHORT));
		assertTrue("normals use BYTE components: " + json,
				json.contains("\"componentType\":" + GltfAccessor.TYPE_BYTE));
		assertTrue("normals are normalized: " + json, json.contains("\"normalized\":true"));

		/* the elements of quantized vertex attributes must be aligned to 4 byte boundaries */

		assertTrue("positions are padded to 8 bytes: " + json, json.contains("\"byteStride\":8"));
		assertTrue("normals are padded to 4 bytes: " + json, json.contains("\"byteStride\":4"));

		/* the root node undoes the quantization */

		assertTrue("the root node is scaled: " + json, json.contains("\"scale\":["));

	}

	@Test
	public void testNoQuantizationAtHighLod() throws IOException {

		/* quantizing would cost geometric precision, which matters at the levels of detail
		 * that are viewed from close up */

		String json = writeGltf(4, KHR_MESH_QUANTIZATION);

		assertFalse("no extension is used: " + json, json.contains(KHR_MESH_QUANTIZATION));
		assertTrue("positions use FLOAT components: " + json,
				json.contains("\"componentType\":" + GltfAccessor.TYPE_FLOAT));

	}

	@Test
	public void testNoQuantizationWithoutWhitelist() throws IOException {

		assertFalse(writeGltf(1).contains(KHR_MESH_QUANTIZATION));
		assertFalse(writeGltf(1, "KHR_texture_transform").contains(KHR_MESH_QUANTIZATION));

	}

	@Test
	public void testGeometryIsPreserved() throws IOException {

		File file = writeGltfFile(".glb", 1, KHR_MESH_QUANTIZATION);
		List<TriangleXYZ> result = trianglesOf(GltfModel.loadFromFile(file));

		assertEquals(TRIANGLES.size(), result.size());

		/* the triangles are compared by their side lengths because reading a file back in as a model
		 * applies the coordinate conventions for models, which rotate the geometry.
		 * Positions are quantized to 1/65535 of the largest extent of the scene, which is 1000 m here. */

		List<Double> expectedSides = sideLengths(TRIANGLES);
		List<Double> actualSides = sideLengths(result);

		for (int i = 0; i < expectedSides.size(); i++) {
			assertEquals("side " + i + " of the quantized geometry",
					expectedSides.get(i), actualSides.get(i), 0.1);
		}

	}

	@Test
	public void testNormalsArePreserved() throws IOException {

		File file = writeGltfFile(".glb", 1, KHR_MESH_QUANTIZATION);

		/* both triangles are almost horizontal, so all normals must still point almost straight up
		 * despite being stored with only 8 bits per component */

		for (TriangleXYZ t : trianglesOf(GltfModel.loadFromFile(file))) {
			assertEquals("the triangle is still almost horizontal: " + t,
					1.0, Math.abs(t.getNormal().y), 0.01);
		}

	}

	private static List<Double> sideLengths(List<TriangleXYZ> triangles) {
		List<Double> result = new ArrayList<>();
		for (TriangleXYZ t : triangles) {
			result.add(t.v1.distanceTo(t.v2));
			result.add(t.v2.distanceTo(t.v3));
			result.add(t.v3.distanceTo(t.v1));
		}
		result.sort(null);
		return result;
	}

	private static List<TriangleXYZ> trianglesOf(GltfModel model) {
		List<TriangleXYZ> result = new ArrayList<>();
		model.getMeshes().forEach(m -> result.addAll(m.geometry.asTriangles().triangles));
		return result;
	}

	/** writes {@link #TRIANGLES} to a glTF file and returns the JSON, with all whitespace removed */
	private static String writeGltf(int lod, String... extensionWhitelist) throws IOException {
		File file = writeGltfFile(".gltf", lod, extensionWhitelist);
		return Files.readString(file.toPath(), StandardCharsets.UTF_8).replaceAll("\\s", "");
	}

	private static File writeGltfFile(String fileExtension, int lod, String... extensionWhitelist) {

		File tempFile = createTempFile(fileExtension);

		Material material = new Material(FLAT, Color.WHITE);

		var builder = new TriangleGeometry.Builder(material.textureLayers().size(), null, FLAT);
		builder.addTriangles(TRIANGLES);

		MapDataBuilder dataBuilder = new MapDataBuilder();
		MapNode node = dataBuilder.createNode(0, 0);
		node.addRepresentation(new TestWorldModule.TestNodeWorldObject(node,
				new Mesh(builder.build(), material)));

		Scene scene = new Scene(null, dataBuilder.build());

		var properties = new HashMap<String, Object>(Map.of("lod", lod));
		if (extensionWhitelist.length > 0) {
			properties.put("gltfExtensionWhitelist", String.join(";", extensionWhitelist));
		}

		var output = new GltfOutput(tempFile);
		output.setConfiguration(new O2WConfig(properties));
		output.outputScene(scene);

		return tempFile;

	}

}
