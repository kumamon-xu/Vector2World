package org.osm2world.scene.mesh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Map;

import org.junit.Test;
import org.osm2world.map_data.creation.MapDataBuilder;
import org.osm2world.scene.Scene;
import org.osm2world.util.test.TestWorldModule;

public class MeshWithMetadataTest {

	@Test
	public void testMeshMetadata() {

		var builder = new MapDataBuilder();
		var node = builder.createNode(0, 0);

		node.addRepresentation(new TestWorldModule.TestNodeWorldObject(node));
		Scene scene = new Scene(null, builder.build());

		assertFalse(scene.getMeshesWithMetadata().isEmpty());
		assertEquals(Map.of("testKey", "1"), scene.getMeshesWithMetadata().get(0).metadata().extraProperties());

	}

}
