package org.osm2world.scene.mesh;

import static java.util.stream.Collectors.toList;
import static org.osm2world.scene.mesh.Geometry.combine;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.osm2world.map_data.data.MapRelationElement;
import org.osm2world.world.data.WorldObject;

import com.google.common.base.Objects;

public record MeshWithMetadata(@Nonnull Mesh mesh, @Nonnull MeshMetadata metadata) implements MeshOrMeshWithMetadata {

	public record MeshMetadata(
			@Nullable MapRelationElement mapElement,
			@Nullable Class<? extends WorldObject> modelClass,
			Map<String, Object> extraProperties) {

		public MeshMetadata(@Nullable MapRelationElement mapElement,
				@Nullable Class<? extends WorldObject> modelClass) {
			this(mapElement, modelClass, Map.of());
		}

		@Override
		public @Nonnull String toString() {
			return "{" + mapElement + ", "
					+ (modelClass == null ? null : modelClass.getSimpleName())
					+ (extraProperties.isEmpty() ? "" : (", " + extraProperties)) + "}";
		}

	}

	public MeshWithMetadata(Mesh mesh, MeshMetadata metadata) {
		if (mesh == null || metadata == null) throw new NullPointerException();
		this.mesh = mesh;
		this.metadata = metadata;
	}

	public static MeshWithMetadata merge(List<MeshWithMetadata> meshes) {

		if (meshes.isEmpty()) throw new IllegalArgumentException();

		MeshMetadata metadata = (meshes.stream().allMatch(m -> Objects.equal(m.metadata, meshes.get(0).metadata)))
				? meshes.get(0).metadata
				: new MeshMetadata(null, null);

		Geometry mergedGeometry = combine(meshes.stream().map(m -> m.mesh.geometry).collect(toList()));
		Mesh mergedMesh = new Mesh(mergedGeometry, meshes.get(0).mesh.material);

		return new MeshWithMetadata(mergedMesh, metadata);

	}

}
