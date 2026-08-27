package org.osm2world.buildingtiler.osm2world;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.osm2world.O2WConverter;
import org.osm2world.buildingtiler.domain.BuildingFeature;
import org.osm2world.buildingtiler.modeling.OsmTagMapper;
import org.osm2world.conversion.O2WConfig;
import org.osm2world.map_data.creation.MapDataBuilder;
import org.osm2world.map_data.data.MapNode;
import org.osm2world.map_data.data.TagSet;
import org.osm2world.math.VectorXZ;
import org.osm2world.math.geo.LatLon;
import org.osm2world.math.geo.MetricMapProjection;
import org.osm2world.math.geo.TileNumber;
import org.osm2world.math.shapes.AxisAlignedRectangleXZ;
import org.osm2world.scene.Scene;
import org.osm2world.scene.mesh.LevelOfDetail;

public final class Osm2WorldEngineAdapter {

	private final OsmTagMapper tagMapper;

	public Osm2WorldEngineAdapter(OsmTagMapper tagMapper) {
		this.tagMapper = tagMapper;
	}

	public GeneratedTile generate(TileNumber tile, List<BuildingFeature> features, LevelOfDetail lod) {
		return generate(tile, features, lod, false);
	}

	public GeneratedTile generate(TileNumber tile, List<BuildingFeature> features, LevelOfDetail lod,
			boolean clipToBounds) {
		if (features.isEmpty()) throw new IllegalArgumentException("A tile needs at least one building");

		MetricMapProjection projection = new MetricMapProjection(tile.latLonBounds().getCenter());
		MapDataBuilder builder = new MapDataBuilder();
		List<String> warnings = new ArrayList<>();
		BoundsAccumulator bounds = new BoundsAccumulator();
		int modeledFeatures = 0;

		for (BuildingFeature feature : features) {
			try {
				TagSet tags = tagMapper.toTags(feature);
				Geometry geometry = feature.geometryWgs84();
				if (geometry instanceof Polygon polygon) {
					addPolygon(builder, polygon, projection, tags, bounds);
				} else if (geometry instanceof MultiPolygon multiPolygon) {
					for (int i = 0; i < multiPolygon.getNumGeometries(); i++) {
						addPolygon(builder, (Polygon)multiPolygon.getGeometryN(i), projection, tags, bounds);
					}
				}
				modeledFeatures++;
			} catch (RuntimeException exception) {
				warnings.add(feature.id() + ": " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
			}
		}

		if (modeledFeatures == 0 || !bounds.initialized()) {
			throw new IllegalStateException("Tile " + tile + " contains no modelable buildings");
		}

		AxisAlignedRectangleXZ boundary = clipToBounds
				? tileBoundary(tile, projection)
				: bounds.toRectangle().pad(1.0);
		O2WConfig config = configuration(lod, clipToBounds);
		var mapData = builder.build(boundary);
		O2WConverter converter = new O2WConverter();
		converter.setConfig(config);
		Scene scene = converter.convert(mapData, projection);
		int meshCount = scene.getMeshes(config).size();
		if (meshCount == 0) throw new IllegalStateException("OSM2World produced no meshes for tile " + tile);

		return new GeneratedTile(tile, scene, projection, boundary, config, modeledFeatures, meshCount,
				List.copyOf(warnings));
	}

	private static void addPolygon(MapDataBuilder builder, Polygon polygon, MetricMapProjection projection,
			TagSet tags, BoundsAccumulator bounds) {
		List<MapNode> outer = toNodes(builder, polygon.getExteriorRing().getCoordinates(), projection, bounds);
		List<List<MapNode>> holes = new ArrayList<>(polygon.getNumInteriorRing());
		for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
			holes.add(toNodes(builder, polygon.getInteriorRingN(i).getCoordinates(), projection, bounds));
		}
		if (holes.isEmpty()) {
			builder.createWayArea(outer, tags);
		} else {
			builder.createMultipolygonArea(outer, holes, tags);
		}
	}

	private static List<MapNode> toNodes(MapDataBuilder builder, Coordinate[] coordinates,
			MetricMapProjection projection, BoundsAccumulator bounds) {
		int count = coordinates.length;
		if (count > 1 && coordinates[0].equals2D(coordinates[count - 1])) count--;
		if (count < 3) throw new IllegalArgumentException("A ring needs at least three distinct coordinates");

		List<MapNode> nodes = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			Coordinate coordinate = coordinates[i];
			VectorXZ local = projection.toXZ(coordinate.y, coordinate.x);
			bounds.include(local);
			nodes.add(builder.createNode(local.x, local.z));
		}
		return nodes;
	}

	private static AxisAlignedRectangleXZ tileBoundary(TileNumber tile, MetricMapProjection projection) {
		var geographic = tile.latLonBounds();
		VectorXZ minimum = projection.toXZ(geographic.getMin().lat, geographic.getMin().lon);
		VectorXZ maximum = projection.toXZ(geographic.getMax().lat, geographic.getMax().lon);
		return new AxisAlignedRectangleXZ(minimum.x, minimum.z, maximum.x, maximum.z);
	}

	private static O2WConfig configuration(LevelOfDetail lod, boolean clipToBounds) {
		return new O2WConfig(Map.of(
				"lod", lod.ordinal(),
				"keepOsmElements", false,
				"clipToBounds", clipToBounds,
				"gltfExtensionWhitelist", "KHR_mesh_quantization",
				"renderUnderground", false));
	}

	public record GeneratedTile(
			TileNumber tile,
			Scene scene,
			MetricMapProjection projection,
			AxisAlignedRectangleXZ boundary,
			O2WConfig configuration,
			int modeledFeatures,
			int meshCount,
			List<String> warnings) {
	}

	private static final class BoundsAccumulator {
		private double minX = Double.POSITIVE_INFINITY;
		private double minZ = Double.POSITIVE_INFINITY;
		private double maxX = Double.NEGATIVE_INFINITY;
		private double maxZ = Double.NEGATIVE_INFINITY;

		void include(VectorXZ point) {
			minX = Math.min(minX, point.x);
			minZ = Math.min(minZ, point.z);
			maxX = Math.max(maxX, point.x);
			maxZ = Math.max(maxZ, point.z);
		}

		boolean initialized() {
			return Double.isFinite(minX);
		}

		AxisAlignedRectangleXZ toRectangle() {
			return new AxisAlignedRectangleXZ(minX, minZ, maxX, maxZ);
		}
	}

}
