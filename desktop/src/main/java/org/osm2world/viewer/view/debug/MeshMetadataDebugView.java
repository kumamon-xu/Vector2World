package org.osm2world.viewer.view.debug;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import javax.annotation.Nullable;

import org.osm2world.output.jogl.JOGLOutput;
import org.osm2world.scene.color.Color;
import org.osm2world.scene.material.Material;
import org.osm2world.scene.material.Material.Interpolation;
import org.osm2world.scene.mesh.Mesh;
import org.osm2world.scene.mesh.MeshWithMetadata;
import org.osm2world.scene.mesh.TriangleGeometry;

public abstract class MeshMetadataDebugView extends StaticDebugView {

	public MeshMetadataDebugView(String label) {
		super(label, "colors meshes based on " + label);
	}

	@Override
	protected final void fillOutput(JOGLOutput output) {

		var colorScheme = new RandomColorScheme();

		for (var m : scene.getMeshesWithMetadata()) {

			if (!m.mesh().lodRange.contains(config.lod())) continue;

			String value = metadataValueFor(m);

			if (value != null) {
				Color color = colorScheme.getOrCreateColor(value);
				TriangleGeometry g = m.mesh().geometry.asTriangles();
				g = new TriangleGeometry(g.triangles, Interpolation.FLAT, List.of(), List.of());
				output.drawMesh(new Mesh(g, new Material(Interpolation.FLAT, color)));
			}

		}

	}

	abstract protected @Nullable String metadataValueFor(MeshWithMetadata m);

	public static class LevelMetadataDebugView extends MeshMetadataDebugView {

		public LevelMetadataDebugView() {
			super("Level");
		}

		@Override
		protected String metadataValueFor(MeshWithMetadata m) {
			Object levelValue = m.metadata().extraProperties().get("level");
			if (levelValue instanceof String levelString) {
				Set<String> values = Set.of(levelString.split(";"));
				OptionalInt minValue = values.stream().mapToInt(Integer::parseInt).min();
				if (minValue.isPresent()) {
					return String.valueOf(minValue.getAsInt());
				}
			}
			return null;
		}

	}

	public static class OsmIdMetadataDebugView extends MeshMetadataDebugView {

		public OsmIdMetadataDebugView() {
			super("OSM ID");
		}

		@Override
		protected String metadataValueFor(MeshWithMetadata m) {
			var mapElement = m.metadata().mapElement();
			return mapElement != null ? mapElement.toString() : null;
		}

	}

	public static class ModelClassMetadataDebugView extends MeshMetadataDebugView {

		public ModelClassMetadataDebugView() {
			super("Model class");
		}

		@Override
		protected String metadataValueFor(MeshWithMetadata m) {
			var modelClass = m.metadata().modelClass();
			return modelClass != null ? modelClass.getSimpleName() : null;
		}

	}

}
