package org.osm2world.output.common;

import static org.osm2world.scene.mesh.MeshWithMetadata.MeshMetadata;

import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

import org.osm2world.output.Output;
import org.osm2world.scene.mesh.Mesh;
import org.osm2world.scene.mesh.MeshOrMeshWithMetadata;
import org.osm2world.scene.mesh.MeshStore;
import org.osm2world.scene.mesh.MeshWithMetadata;
import org.osm2world.world.data.WorldObject;

/**
 * An {@link Output} that collects everything that is being drawn as {@link Mesh}es.
 * {@link Mesh}es are in-memory representation of 3D geometry suitable for use with typical graphics APIs.
 */
public class MeshOutput extends AbstractOutput implements DrawBasedOutput {

	private final BiPredicate<WorldObject, MeshOrMeshWithMetadata> worldObjectFilter;

	protected final MeshStore meshStore = new MeshStore();

	protected WorldObject currentWorldObject = null;

	/**
	 * @param worldObjectFilter  only {@link WorldObject}s matching this filter will be included in the output
	 */
	public MeshOutput(BiPredicate<WorldObject, MeshOrMeshWithMetadata> worldObjectFilter) {
		this.worldObjectFilter = worldObjectFilter;
	}

	public MeshOutput() {
		this((o,m) -> true);
	}

	@Override
	public boolean includeMesh(WorldObject object, MeshOrMeshWithMetadata mesh) {
		return worldObjectFilter.test(object, mesh);
	}

	@Override
	public void beginObject(WorldObject object) {
		this.currentWorldObject = object;
	}

	@Override
	public void drawMesh(MeshWithMetadata mesh) {

		MeshMetadata metadata = mesh.metadata();

		if (currentWorldObject != null && metadata.modelClass() == null && metadata.mapElement() == null) {
			metadata = new MeshMetadata(currentWorldObject.getPrimaryMapElement().getElementWithId(),
					currentWorldObject.getClass(), metadata.extraProperties());
		}

		meshStore.addMesh(mesh.mesh(), metadata);

	}

	@Override
	public void drawMesh(Mesh mesh) {
		this.drawMesh(new MeshWithMetadata(mesh, new MeshMetadata(null, Map.of())));
	}

	public List<Mesh> getMeshes() {
		return meshStore.meshes();
	}

	public List<MeshWithMetadata> getMeshesWithMetadata() {
		return meshStore.meshesWithMetadata();
	}

}