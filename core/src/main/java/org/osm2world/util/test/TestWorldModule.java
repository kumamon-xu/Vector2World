package org.osm2world.util.test;

import static java.util.Collections.singletonList;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.osm2world.map_data.data.MapNode;
import org.osm2world.map_elevation.data.GroundState;
import org.osm2world.math.VectorXYZ;
import org.osm2world.math.shapes.TriangleXYZ;
import org.osm2world.scene.color.Color;
import org.osm2world.scene.material.BlankTexture;
import org.osm2world.scene.material.Material;
import org.osm2world.scene.material.TextureLayer;
import org.osm2world.scene.mesh.Mesh;
import org.osm2world.scene.mesh.MeshOrMeshWithMetadata;
import org.osm2world.scene.texcoord.NamedTexCoordFunction;
import org.osm2world.scene.texcoord.TexCoordUtil;
import org.osm2world.world.data.NoOutlineNodeWorldObject;
import org.osm2world.world.data.ProceduralWorldObject;
import org.osm2world.world.data.WorldObject;
import org.osm2world.world.modules.common.AbstractModule;

/**
 * a world module for unit tests that produces simple and predictable {@link WorldObject}s
 */
public class TestWorldModule extends AbstractModule {

	@Override
	protected void applyToNode(MapNode node) {
		node.addRepresentation(new TestNodeWorldObject(node));
	}

	public static class TestNodeWorldObject extends NoOutlineNodeWorldObject implements ProceduralWorldObject {

		private final @Nullable Mesh mesh;

		public TestNodeWorldObject(MapNode node, @Nullable Mesh mesh) {
			super(node);
			this.mesh = mesh;
		}

		public TestNodeWorldObject(MapNode node) {
			this(node, null);
		}

		@Override
		public GroundState getGroundState() {
			return GroundState.ON;
		}

		@Override
		public void buildMeshesAndModels(Target target) {

			target.setCurrentMetadata(Map.of("testKey", "1"));

			VectorXYZ base = node.getPos().xyz(0);

			Material material = new Material(Material.Interpolation.FLAT, Color.WHITE, Material.Transparency.FALSE,
					List.of(new TextureLayer(BlankTexture.INSTANCE,null, null, null, false)));

			TriangleXYZ triangle = new TriangleXYZ(base, base.add(0, 1, 0), base.add(1, 1, 0));
			List<TriangleXYZ> ts = singletonList(triangle);
			target.drawTriangles(material, ts, TexCoordUtil.triangleTexCoordLists(ts, material, NamedTexCoordFunction.SLOPED_TRIANGLES));

		}

		@Override
		public List<? extends MeshOrMeshWithMetadata> buildMeshes() {
			if (mesh == null) {
				return ProceduralWorldObject.super.buildMeshes();
			} else {
				return singletonList(mesh);
			}
		}
	}

}
