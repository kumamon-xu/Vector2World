package org.osm2world.scene.mesh;

public sealed interface MeshOrMeshWithMetadata permits Mesh, MeshWithMetadata {

	default Mesh asMesh() {
		return this instanceof MeshWithMetadata m ? m.mesh() : (Mesh) this;
	}

}
