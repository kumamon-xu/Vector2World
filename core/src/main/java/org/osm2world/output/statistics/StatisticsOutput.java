package org.osm2world.output.statistics;

import static org.osm2world.util.FaultTolerantIterationUtil.forEach;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.osm2world.output.common.AbstractOutput;
import org.osm2world.scene.Scene;
import org.osm2world.scene.material.Material;
import org.osm2world.scene.mesh.ExtrusionGeometry;
import org.osm2world.scene.mesh.LevelOfDetail;
import org.osm2world.scene.mesh.Mesh;
import org.osm2world.scene.mesh.ShapeGeometry;
import org.osm2world.world.data.WorldObject;

/**
 * a target that simply counts the primitives that are sent to it
 * to create statistics.
 */
public class StatisticsOutput extends AbstractOutput {

	public final @Nullable LevelOfDetail lod;

	private final long[] globalCounts = new long[Stat.values().length];
	private final Map<Material, long[]> countsPerMaterial = new HashMap<>();
	private final Map<Class<? extends WorldObject>, long[]> countsPerClass = new HashMap<>();

	public StatisticsOutput(@Nullable LevelOfDetail lod) {
		this.lod = lod;
	}

	public StatisticsOutput() {
		this(null);
	}

	public enum Stat {

		OBJECT_COUNT {
			@Override public long countObject(WorldObject object) {
				return 1;
			}
		},

		TOTAL_TRIANGLE_COUNT {
			@Override public long countMesh(Mesh mesh, int triangles) {
				return triangles;
			}
		},

		EXTRUSION_TRIANGLE_COUNT {
			@Override public long countMesh(Mesh mesh, int triangles) {
				if (mesh.geometry instanceof ExtrusionGeometry) {
					return triangles;
				} else {
					return 0;
				}
			}
		},

		SHAPE_TRIANGLE_COUNT {
			@Override public long countMesh(Mesh mesh, int triangles) {
				if (mesh.geometry instanceof ShapeGeometry) {
					return triangles;
				} else {
					return 0;
				}
			}
		};

		public long countObject(WorldObject object) {
			return 0;
		}

		public long countMesh(Mesh mesh, int triangles) {
			return 0;
		}

	}

	public LevelOfDetail getLod() {
		if (lod != null) {
			return lod;
		} else {
			return getConfig().lod();
		}
	}

	@Override
	public void outputScene(Scene scene) {

		forEach(scene.getWorldObjects(false), (WorldObject object) -> {

			handleObject(object);

			for (var m : object.buildMeshes(true)) {
				if (getLod() == null || m.asMesh().lodRange.contains(getLod())) {
					handleMesh(object, m.asMesh());
				}
			}

		});

	}

	private void handleMesh(WorldObject object, Mesh mesh) {

		Material material = mesh.material;
		int triangles = mesh.geometry.asTriangles().triangles.size();

		countsPerMaterial.putIfAbsent(material, new long[Stat.values().length]);

		for (Stat stat : Stat.values()) {

			long count = stat.countMesh(mesh, triangles);

			globalCounts[stat.ordinal()] += count;

			if (material != null) {
				countsPerMaterial.get(material)[stat.ordinal()] += count;
			}

			countsPerClass.get(object.getClass())[stat.ordinal()] += count;

		}
	}

	public void handleObject(WorldObject object) {

		if (object != null) {

			Class<? extends WorldObject> currentClass = object.getClass();

			countsPerClass.putIfAbsent(currentClass, new long[Stat.values().length]);

			for (Stat stat : Stat.values()) {

				long count = stat.countObject(object);

				globalCounts[stat.ordinal()] += count;

				countsPerClass.get(currentClass)[stat.ordinal()] += count;

			}

		}

	}

	public long getGlobalCount(Stat stat) {
		return globalCounts[stat.ordinal()];
	}

	public Set<Material> getKnownMaterials() {
		return countsPerMaterial.keySet();
	}

	public long getCountForMaterial(Material material, Stat stat) {
		return countsPerMaterial.get(material)[stat.ordinal()];
	}

	public Set<Class<? extends WorldObject>> getKnownRenderableClasses() {
		return countsPerClass.keySet();
	}

	public long getCountForClass(Class<?> c, Stat stat) {
		return countsPerClass.get(c)[stat.ordinal()];
	}

}
