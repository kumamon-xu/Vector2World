package org.osm2world.scene;

import static java.util.Objects.requireNonNullElse;
import static org.osm2world.util.FaultTolerantIterationUtil.DEFAULT_EXCEPTION_HANDLER;
import static org.osm2world.util.FaultTolerantIterationUtil.forEach;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.osm2world.conversion.O2WConfig;
import org.osm2world.map_data.data.MapData;
import org.osm2world.map_elevation.data.GroundState;
import org.osm2world.math.geo.MapProjection;
import org.osm2world.math.shapes.AxisAlignedRectangleXZ;
import org.osm2world.scene.mesh.Mesh;
import org.osm2world.scene.mesh.MeshStore;
import org.osm2world.scene.mesh.MeshWithMetadata;
import org.osm2world.world.data.WorldObject;

import com.google.common.collect.Iterables;

/**
 * A 3D scene created from map data.
 * Interim result of an OSM2World run (before it is written to any Output).
 */
public final class Scene {

	private final @Nullable MapProjection mapProjection;
	private final MapData mapData;

	/** caches the result of {@link #getMeshes()} and {@link #getMeshesWithMetadata()} */
	private final Map<O2WConfig, MeshStore> meshStoreCache = new HashMap<>();

	public Scene(@Nullable MapProjection mapProjection, MapData mapData) {
		this.mapProjection = mapProjection;
		this.mapData = mapData;
	}

	/**
	 * the map projection used to convert between geographic coordinates and the scene's coordinate system
	 */
	public @Nullable MapProjection getMapProjection() {
		return mapProjection;
	}

	/**
	 * the scene bounds. Some geometry in the scene may extend beyond those bounds.
	 */
	public AxisAlignedRectangleXZ getBoundary() {
		return mapData.getBoundary();
	}

	/**
	 * returns the underlying {@link MapData}
	 */
	public MapData getMapData() {
		return mapData;
	}

	/**
	 * returns all {@link WorldObject}s in this scene
	 */
	public Iterable<WorldObject> getWorldObjects() {
		return mapData.getWorldObjects();
	}

	/**
	 * returns all {@link WorldObject}s in this scene
	 * @param includeChildObjects  whether the result should include child objects (true), or only top-level objects
	 */
	public Iterable<WorldObject> getWorldObjects(boolean includeChildObjects) {
		if (includeChildObjects) {
			return getWorldObjects();
		} else {
			return Iterables.filter(getWorldObjects(), it -> it.getParent() == null);
		}
	}

	/**
	 * returns all {@link WorldObject}s in this scene which are instances of a certain type.
	 */
	public <T> Iterable<T> getWorldObjects(Class<T> type) {
		return Iterables.filter(getWorldObjects(), type);
	}

	/** @see #getMeshes(O2WConfig) */
	public List<Mesh> getMeshes() {
		return getMeshes(null);
	}
	
	/**
	 * returns the {@link Mesh}es of all world objects
	 * 
	 * @param config  optional configuration object. Some preferences such as {@link O2WConfig#renderUnderground()}
	 *                will affect the result.
	 */
	public List<Mesh> getMeshes(@Nullable O2WConfig config) {
		var meshStore = loadMeshStore(config);
		return meshStore.meshes();
	}

	/** @see #getMeshesWithMetadata(O2WConfig) */
	public List<MeshWithMetadata> getMeshesWithMetadata() {
		return getMeshesWithMetadata(null);
	}

	/**
	 * returns the same meshes as {@link #getMeshes(O2WConfig)}, but includes metadata
	 */
	public List<MeshWithMetadata> getMeshesWithMetadata(@Nullable O2WConfig config) {
		var meshStore = loadMeshStore(config);
		return meshStore.meshesWithMetadata();
	}

	private MeshStore loadMeshStore(@Nullable O2WConfig config) {

		config = requireNonNullElse(config, new O2WConfig());

		if (!this.meshStoreCache.containsKey(config)) {
			var meshStore = sceneToMeshes(this, config, null);
			this.meshStoreCache.put(config, meshStore);
		}

		return this.meshStoreCache.get(config);

	}

	/**
	 * Flattens a scene into a collection of meshes with associated metadata.
	 * Used internally to power methods such as {@link #getMeshes()},
	 * but can also be used directly if the extra control over mesh filtering is necessary.
	 *
	 * @param includeFilter  optional filter to include only some meshes in the result.
	 *                       This filtering occurs in addition to any filtering effects of config options.
	 */
	public static @Nonnull MeshStore sceneToMeshes(@Nonnull Scene scene, @Nonnull O2WConfig config,
			@Nullable BiPredicate<WorldObject, MeshWithMetadata> includeFilter) {

		BiPredicate<WorldObject, MeshWithMetadata> filter = (o, m) ->
				(includeFilter == null || includeFilter.test(o, m))
				&& (config.renderUnderground() || o.getGroundState() != GroundState.BELOW);

		MeshStore meshStore = new MeshStore();

		forEach(scene.getWorldObjects(false), (WorldObject o) -> {
			for (MeshWithMetadata meshWithMetadata : o.buildMeshesWithMetadata(true)) {
				if (filter.test(o, meshWithMetadata)) {
					meshStore.addMesh(meshWithMetadata);
				}
			}
		}, (e, r) -> DEFAULT_EXCEPTION_HANDLER.accept(e, r.getPrimaryMapElement()));

		return meshStore;

	}

}
