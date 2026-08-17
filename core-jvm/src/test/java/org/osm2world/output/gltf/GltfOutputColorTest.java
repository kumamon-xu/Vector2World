package org.osm2world.output.gltf;

import static java.util.Arrays.asList;
import static org.osm2world.scene.color.Color.BLUE;
import static org.osm2world.scene.color.Color.RED;
import static org.junit.Assert.*;
import static org.osm2world.scene.material.DefaultMaterials.STEEL;
import static org.osm2world.util.test.TestFileUtil.createTempFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.junit.Test;
import org.osm2world.conversion.O2WConfig;
import org.osm2world.map_data.creation.MapDataBuilder;
import org.osm2world.map_data.data.MapNode;
import org.osm2world.math.VectorXYZ;
import org.osm2world.math.shapes.TriangleXYZ;
import org.osm2world.scene.Scene;
import org.osm2world.scene.color.Color;
import org.osm2world.scene.color.LColor;
import org.osm2world.scene.material.Material;
import org.osm2world.scene.mesh.Mesh;
import org.osm2world.scene.mesh.TriangleGeometry;
import org.osm2world.util.platform.image.ImageImplementationJvm;
import org.osm2world.util.platform.json.JsonImplementationJvm;
import org.osm2world.util.test.TestWorldModule;

/**
 * tests that a vertex color which is the same for the entire mesh ends up in the material
 * instead of in a COLOR_0 attribute, see {@link GltfOutput}
 */
public class GltfOutputColorTest {

	static {
		JsonImplementationJvm.register();
		ImageImplementationJvm.register();
	}

	@Test
	public void testConstantColorGoesIntoMaterial() throws IOException {

		String json = writeGltf(asList(RED, RED, RED));

		assertFalse("no COLOR_0 attribute for a mesh with a single color", json.contains("COLOR_0"));

		/* the color is stored in the material instead, in linear color space */

		LColor expected = LColor.fromRGB(RED);
		assertTrue("baseColorFactor is present: " + json, json.contains("baseColorFactor"));
		assertTrue("baseColorFactor holds the red component: " + json,
				json.contains(Float.toString(expected.red)));
		assertTrue("baseColorFactor holds the green component: " + json,
				json.contains(Float.toString(expected.green)));

	}

	@Test
	public void testVaryingColorsStayInTheAttribute() throws IOException {

		String json = writeGltf(asList(RED, BLUE, RED));

		assertTrue("COLOR_0 attribute for a mesh with more than one color", json.contains("COLOR_0"));
		assertFalse("no baseColorFactor if the colors vary", json.contains("baseColorFactor"));

	}

	@Test
	public void testNoVertexColors() throws IOException {

		String json = writeGltf((List<Color>) null);

		/* even without explicit vertex colors, the color of the material itself is moved to the vertices
		 * before the output is written. It is the same for all vertices, so it ends up in the material again. */

		assertFalse("no COLOR_0 attribute for the uniform color of a material", json.contains("COLOR_0"));

		LColor expected = LColor.fromRGB(STEEL.defaultAppearance().color());
		assertTrue("baseColorFactor holds the color of the material: " + json,
				json.contains(Float.toString(expected.red)));

	}

	@Test
	public void testDifferentColorsUseDifferentMaterials() throws IOException {

		/* two meshes which are not merged, with the same material but different constant colors.
		 * They must not share a glTF material, as the color is part of the material now. */

		String json = writeGltfWithSeparateElements(asList(RED, RED, RED), asList(BLUE, BLUE, BLUE));

		assertEquals(2, countOccurrences(json, "baseColorFactor"));
		assertFalse(json.contains("COLOR_0"));

		LColor red = LColor.fromRGB(RED), blue = LColor.fromRGB(BLUE);
		assertTrue("the red mesh keeps its color", json.contains(Float.toString(red.red)));
		assertTrue("the blue mesh keeps its color", json.contains(Float.toString(blue.blue)));

	}

	@Test
	public void testSameColorSharesMaterial() throws IOException {

		String json = writeGltfWithSeparateElements(asList(RED, RED, RED), asList(RED, RED, RED));

		assertEquals("meshes with the same material and color share a glTF material",
				1, countOccurrences(json, "baseColorFactor"));

	}

	/**
	 * writes a glTF file containing one mesh per list of vertex colors, and returns its content
	 *
	 * @param colorLists  colors for the 3 vertices of the single triangle of each mesh, each may be null
	 */
	@SafeVarargs
	private static String writeGltf(@Nullable List<Color>... colorLists) throws IOException {
		return writeGltf(false, colorLists);
	}

	/**
	 * variant of {@link #writeGltf(List[])} which keeps the meshes from being merged with each other,
	 * by putting each of them on a separate map element and keeping those elements apart
	 */
	@SafeVarargs
	private static String writeGltfWithSeparateElements(@Nullable List<Color>... colorLists) throws IOException {
		return writeGltf(true, colorLists);
	}

	@SafeVarargs
	private static String writeGltf(boolean separateElements, @Nullable List<Color>... colorLists)
			throws IOException {

		File tempFile = createTempFile(".gltf");

		Material material = STEEL.defaultAppearance();

		MapDataBuilder dataBuilder = new MapDataBuilder();
		MapNode node = dataBuilder.createNode(0, 0);

		int nodeCounter = 0;

		for (List<Color> colors : colorLists) {

			if (separateElements) {
				node = dataBuilder.createNode(10 * nodeCounter++, 0);
			}

			var triangle = new TriangleXYZ(
					new VectorXYZ(0, 0, 0), new VectorXYZ(1, 0, 0), new VectorXYZ(0, 1, 0));

			var builder = new TriangleGeometry.Builder(material.textureLayers().size(), null,
					material.interpolation());
			builder.addTriangles(List.of(triangle), null, colors);

			node.addRepresentation(new TestWorldModule.TestNodeWorldObject(node,
					new Mesh(builder.build(), material)));

		}

		Scene scene = new Scene(null, dataBuilder.build());

		var output = new GltfOutput(tempFile);
		output.setConfiguration(new O2WConfig(Map.of("keepOsmElements", separateElements)));
		output.outputScene(scene);

		return Files.readString(tempFile.toPath(), StandardCharsets.UTF_8);

	}

	private static int countOccurrences(String haystack, String needle) {
		int count = 0, index = 0;
		while ((index = haystack.indexOf(needle, index)) >= 0) {
			count++;
			index += needle.length();
		}
		return count;
	}

}
