package org.osm2world.output.gltf;

import static org.osm2world.math.VectorXYZ.NULL_VECTOR;
import static org.osm2world.output.common.ResourceOutputSettings.ResourceOutputMode;
import static org.osm2world.scene.material.DefaultMaterials.STEEL;
import static org.osm2world.util.test.TestFileUtil.createTempFile;

import java.io.File;
import java.util.Map;

import org.junit.Test;
import org.osm2world.conversion.O2WConfig;
import org.osm2world.map_data.creation.MapDataBuilder;
import org.osm2world.map_data.data.MapNode;
import org.osm2world.scene.Scene;
import org.osm2world.scene.material.Material;
import org.osm2world.scene.mesh.ExtrusionGeometry;
import org.osm2world.scene.mesh.Mesh;
import org.osm2world.util.platform.image.ImageImplementationJvm;
import org.osm2world.util.platform.json.JsonImplementationJvm;
import org.osm2world.util.test.TestWorldModule;

public class GltfOutputTest {

	static {
		JsonImplementationJvm.register();
		ImageImplementationJvm.register();
	}

	@Test
	public void testSimpleGltf() {
		createTemporaryTestGltfs(".gltf");
	}

	@Test
	public void testSimpleGlb() {
		createTemporaryTestGltfs(".glb");
	}

	@Test
	public void testSimpleGltfGz() {
		createTemporaryTestGltfs(".gltf.gz");
	}

	@Test
	public void testSimpleGlbGz() {
		createTemporaryTestGltfs(".glb.gz");
	}

	@Test
	public void testSimpleGltfZip() {
		createTemporaryTestGltfs(".gltf.zip");
	}

	@Test
	public void testSimpleGlbZip() {
		createTemporaryTestGltfs(".glb.zip");
	}

	private static void createTemporaryTestGltfs(String fileExtension) {
		createTemporaryTestGltf(fileExtension, ResourceOutputMode.EMBED);
		createTemporaryTestGltf(fileExtension, ResourceOutputMode.STORE_SEPARATELY_AND_REFERENCE);
	}

	private static void createTemporaryTestGltf(String fileExtension, ResourceOutputMode resourceOutputMode) {

		File tempFile = createTempFile(fileExtension);

		Material material = STEEL.defaultAppearance();
		var mesh = new Mesh(ExtrusionGeometry.createColumn(
				null, NULL_VECTOR, 10, 2, 0, true, false, null,
						material.textureDimensions()), material);

		MapDataBuilder dataBuilder = new MapDataBuilder();
		MapNode node = dataBuilder.createNode(0, 0);
		node.addRepresentation(new TestWorldModule.TestNodeWorldObject(node, mesh));
		MapNode nodeB = dataBuilder.createNode(0, 5);
		nodeB.addRepresentation(new TestWorldModule.TestNodeWorldObject(node));

		Scene scene = new Scene(null, dataBuilder.build());

		O2WConfig config = new O2WConfig(Map.of(
				"staticResourceOutputMode", resourceOutputMode.toString(),
				"generatedResourceOutputMode", resourceOutputMode.toString()));

		var target = new GltfOutput(tempFile);
		target.setConfiguration(config);
		target.outputScene(scene);

	}

}
