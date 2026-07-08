package org.osm2world.output.common;

import static org.osm2world.scene.mesh.MeshWithMetadata.MeshMetadata;

import java.util.List;
import java.util.function.Predicate;

import org.osm2world.output.Output;
import org.osm2world.scene.mesh.Mesh;
import org.osm2world.scene.mesh.MeshStore;
import org.osm2world.scene.mesh.MeshWithMetadata;
import org.osm2world.world.data.WorldObject;

/**
 * An {@link Output} that collects everything that is being drawn as {@link Mesh}es.
 * {@link Mesh}es are in-memory representation of 3D geometry suitable for use with typical graphics APIs.
 */
public class MeshOutput extends AbstractOutput implements DrawBasedOutput {

	private final Predicate<WorldObject> worldObjectFilter;

	protected final MeshStore meshStore = new MeshStore();

	protected WorldObject currentWorldObject = null;

	/**
	 * @param worldObjectFilter  only {@link WorldObject}s matching this filter will be included in the output
	 */
	public MeshOutput(Predicate<WorldObject> worldObjectFilter) {
		this.worldObjectFilter = worldObjectFilter;
	}

	public MeshOutput() {
		this(x -> true);
	}

	@Override
	public boolean includeObject(WorldObject object) {
		return worldObjectFilter.test(object);
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
		this.drawMesh(new MeshWithMetadata(mesh, new MeshMetadata(null, null)));
	}

	public List<Mesh> getMeshes() {
		return meshStore.meshes();
	}

	public List<MeshWithMetadata> getMeshesWithMetadata() {
		return meshStore.meshesWithMetadata();
	}

}