package org.osm2world.scene.mesh;

import static com.google.common.base.Objects.equal;
import static java.util.stream.Collectors.toList;
import static org.osm2world.scene.mesh.Geometry.combine;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.osm2world.map_data.data.MapRelationElement;
import org.osm2world.world.data.WorldObject;

public record MeshWithMetadata(@Nonnull Mesh mesh, @Nonnull MeshMetadata metadata) implements MeshOrMeshWithMetadata {

	public record ElementMetadata(
			@Nullable MapRelationElement mapElement,
			@Nullable Class<? extends WorldObject> modelClass
	) {}

	public record MeshMetadata(@Nullable ElementMetadata elementMetadata,
			@Nonnull Map<String, Object> extraProperties) {

		public MeshMetadata(@Nullable MapRelationElement mapElement,
				@Nullable Class<? extends WorldObject> modelClass,
				@Nonnull Map<String, Object> extraProperties) {
			this((mapElement == null && modelClass == null) ? null : new ElementMetadata(mapElement, modelClass),
					extraProperties);
		}

		public MeshMetadata(@Nullable MapRelationElement mapElement,
				@Nullable Class<? extends WorldObject> modelClass) {
			this(mapElement, modelClass, Map.of());
		}

		public @Nullable MapRelationElement mapElement() {
			return elementMetadata == null ? null : elementMetadata.mapElement();
		}

		public @Nullable Class<? extends WorldObject> modelClass() {
			return elementMetadata == null ? null : elementMetadata.modelClass();
		}

		@Override
		public @Nonnull String toString() {
			return "{" + mapElement() + ", "
					+ (modelClass() == null ? null : modelClass().getSimpleName())
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

		MeshWithMetadata m0 = meshes.get(0);

		var elementMetadata = meshes.stream().allMatch(m -> equal(m.metadata.elementMetadata, m0.metadata.elementMetadata))
				? m0.metadata.elementMetadata : null;
		Map<String, Object> extraProperties = meshes.stream().allMatch(m -> equal(m.metadata.extraProperties, m0.metadata.extraProperties))
				? m0.metadata.extraProperties : Map.of();

		MeshMetadata metadata = new MeshMetadata(elementMetadata, extraProperties);

		Geometry mergedGeometry = combine(meshes.stream().map(m -> m.mesh.geometry).collect(toList()));
		Mesh mergedMesh = new Mesh(mergedGeometry, m0.mesh.material);

		return new MeshWithMetadata(mergedMesh, metadata);

	}

}
