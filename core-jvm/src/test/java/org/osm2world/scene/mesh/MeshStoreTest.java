package org.osm2world.scene.mesh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.osm2world.scene.material.Material.Interpolation.FLAT;
import static org.osm2world.scene.mesh.MeshStore.ClipToBounds.clipToBounds;
import static org.osm2world.scene.mesh.MeshStore.ClipToBounds.getSegmentsCCW;
import static org.osm2world.scene.mesh.MeshWithMetadata.MeshMetadata;
import static org.osm2world.test.TestUtil.assertAlmostEquals;

import java.util.*;

import org.junit.Test;
import org.osm2world.math.VectorXYZ;
import org.osm2world.math.VectorXZ;
import org.osm2world.math.shapes.AxisAlignedRectangleXZ;
import org.osm2world.math.shapes.TriangleXYZ;
import org.osm2world.math.shapes.TriangleXZ;
import org.osm2world.scene.color.Color;
import org.osm2world.scene.material.DefaultMaterials;
import org.osm2world.scene.material.Material;

public class MeshStoreTest {

	@Test
	public void testMergeMeshes_Metadata() {

		Material material = DefaultMaterials.CONCRETE.defaultAppearance();

		var metadataA = new MeshMetadata(null, null, Map.of("k", "value"));
		var metadataB = new MeshMetadata(null, null, Map.of("k", "other"));

		Mesh meshA0 = new Mesh(randomTriangleGeometry(0), material);
		Mesh meshA1 = new Mesh(randomTriangleGeometry(1), material);
		Mesh meshB0 = new Mesh(randomTriangleGeometry(2), material);

		var store = new MeshStore(List.of(
				new MeshWithMetadata(meshA0, metadataA),
				new MeshWithMetadata(meshA1, metadataA),
				new MeshWithMetadata(meshB0, metadataB)
		));

		var result0 = store.process(List.of(new MeshStore.MergeMeshes(Set.of())));
		assertEquals(2, result0.meshes().size());

		var result1 = store.process(List.of(new MeshStore.MergeMeshes(EnumSet.of(MeshStore.MergeMeshes.MergeOption.MERGE_METADATA_PROPERTIES))));
		assertEquals(1, result1.meshes().size());

	}

	@Test
	public void testClipToBounds() {

		var tOrig = new TriangleXYZ(new VectorXYZ(0, 0, 0), new VectorXYZ(10, 0, 0), new VectorXYZ(0, 0, 10));

		var bbox = new AxisAlignedRectangleXZ(-10, 5, +10, 15);
		var result1 = clipToBounds(tOrig, getSegmentsCCW(bbox));
		assertEquals(1, result1.size());
		var expectedResult1 = new TriangleXYZ(new VectorXYZ(0, 0, 5), new VectorXYZ(5, 0, 5), new VectorXYZ(0, 0, 10));
		assertAlmostEquals(expectedResult1.getCenter(), result1.iterator().next().getCenter());
		assertAlmostEquals(expectedResult1.getArea(), result1.iterator().next().getArea());

		var tSmall = new TriangleXZ(new VectorXZ(2, 2), new VectorXZ(8, 2), new VectorXZ(2, 8));
		var result2 = clipToBounds(tOrig, getSegmentsCCW(tSmall));
		assertEquals(1, result2.size());
		assertAlmostEquals(tSmall.getCenter().xyz(0), result2.iterator().next().getCenter());
		assertAlmostEquals(tSmall.getArea(), result2.iterator().next().getArea());

	}

	@Test
	public void testEmulateDoubleSidedMaterials() {

		var tOrig = new TriangleXYZ(new VectorXYZ(0, 0, 0), new VectorXYZ(10, 0, 0), new VectorXYZ(0, 0, 10));

		var geometryBuilder = new TriangleGeometry.Builder(0, null, FLAT);
		geometryBuilder.addTriangles(List.of(tOrig));

		var material = new Material(FLAT, Color.WHITE).makeDoubleSided();

		var mesh = new Mesh(geometryBuilder.build(), material);
		var metadata = new MeshMetadata(null, Map.of());

		MeshStore input = new MeshStore(List.of(new MeshWithMetadata(mesh, metadata)));

		MeshStore result = input.process(List.of(new MeshStore.EmulateDoubleSidedMaterials()));

		assertEquals(2, result.meshes().size());
		assertTrue(result.meshes().stream().noneMatch(m -> m.material.doubleSided()));

	}

	private static TriangleGeometry randomTriangleGeometry(long seed) {

		var r = new Random(seed);

		VectorXYZ[] vs = new VectorXYZ[3];

		for (int i = 0; i <= 1; i++) {
			vs[i] = new VectorXYZ(r.nextDouble(-10, 10), r.nextDouble(-10, 10), r.nextDouble(-10, 10));
		}

		// place the third point at a right angle to avoid degenerate triangles
		vs[2] = vs[0].add(vs[0].crossNormalized(vs[1])).mult(r.nextDouble(0.1, 5));

		var builder = new TriangleGeometry.Builder(0, Color.WHITE, FLAT);
		builder.addTriangles(new TriangleXYZ(vs[0], vs[1], vs[2]));
		return builder.build();

	}

}