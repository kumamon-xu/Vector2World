package org.osm2world.output.common;

import java.util.List;
import java.util.function.BiPredicate;

import javax.annotation.Nullable;

import org.osm2world.output.Output;
import org.osm2world.scene.Scene;
import org.osm2world.scene.mesh.Mesh;
import org.osm2world.scene.mesh.MeshStore;
import org.osm2world.scene.mesh.MeshWithMetadata;
import org.osm2world.world.data.WorldObject;

/**
 * An {@link Output} that collects everything that is being drawn as {@link Mesh}es.
 * {@link Mesh}es are in-memory representation of 3D geometry suitable for use with typical graphics APIs.
 */
public class MeshOutput extends AbstractOutput {

	private final @Nullable BiPredicate<WorldObject, MeshWithMetadata> worldObjectFilter;

	protected MeshStore meshStore = new MeshStore();

	/**
	 * @param worldObjectFilter  only {@link WorldObject}s matching this filter will be included in the output
	 */
	public MeshOutput(@Nullable BiPredicate<WorldObject, MeshWithMetadata> worldObjectFilter) {
		this.worldObjectFilter = worldObjectFilter;
	}

	@Override
	public void outputScene(Scene scene) {
		this.meshStore = Scene.sceneToMeshes(scene, this.config, worldObjectFilter);
	}

	public List<Mesh> getMeshes() {
		return meshStore.meshes();
	}

	public List<MeshWithMetadata> getMeshesWithMetadata() {
		return meshStore.meshesWithMetadata();
	}

}