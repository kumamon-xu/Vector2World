package org.osm2world.buildingtiler.osm2world;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.osm2world.O2WConverter;
import org.osm2world.buildingtiler.domain.BuildingFeature;
import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.domain.StyledBuilding;
import org.osm2world.buildingtiler.modeling.BuildingRuleEngine;
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
	private final BuildingRuleEngine ruleEngine;

	public Osm2WorldEngineAdapter(OsmTagMapper tagMapper) {
		this(tagMapper, new BuildingRuleEngine());
	}

	public Osm2WorldEngineAdapter(OsmTagMapper tagMapper, BuildingRuleEngine ruleEngine) {
		this.tagMapper = tagMapper;
		this.ruleEngine = ruleEngine;
	}

	public GeneratedTile generate(TileNumber tile, List<BuildingFeature> features, LevelOfDetail lod) {
		return generate(tile, features, lod, false);
	}

	public GeneratedTile generate(TileNumber tile, List<BuildingFeature> features, LevelOfDetail lod,
			boolean clipToBounds) {
		return generate(tile, features, ModelingConfig.defaults().withLod(lod.ordinal()), clipToBounds);
	}

	public GeneratedTile generate(TileNumber tile, List<BuildingFeature> features, ModelingConfig config,
			boolean clipToBounds) {
		if (tile == null || features == null || config == null) {
			throw new IllegalArgumentException("Tile, features and modeling config are required");
		}
		if (features.isEmpty()) return GeneratedTile.empty(tile);
		MetricMapProjection projection = new MetricMapProjection(tile.latLonBounds().getCenter());
		AxisAlignedRectangleXZ fixedBoundary = clipToBounds ? tileBoundary(tile, projection) : null;
		GeneratedModel generated = generateModel(tile.toString(), projection, fixedBoundary, features, config, clipToBounds);
		return new GeneratedTile(tile, generated.scene(), projection, generated.boundary(), generated.configuration(),
				generated.modeledFeatures(), generated.meshCount(), generated.warnings(), generated.failures(),
				generated.styles(), generated.metrics());
	}

	public GeneratedRegion generateRegion(String regionId, List<BuildingFeature> features, ModelingConfig config) {
		if (regionId == null || regionId.isBlank() || features == null || features.isEmpty() || config == null) {
			throw new IllegalArgumentException("Region id, non-empty features and modeling config are required");
		}
		Envelope envelope = new Envelope();
		features.forEach(feature -> envelope.expandToInclude(feature.geometryWgs84().getEnvelopeInternal()));
		MetricMapProjection projection = new MetricMapProjection(new LatLon(
				(envelope.getMinY() + envelope.getMaxY()) / 2.0,
				(envelope.getMinX() + envelope.getMaxX()) / 2.0));
		GeneratedModel generated = generateModel(regionId, projection, null, features, config, false);
		return new GeneratedRegion(regionId, generated.scene(), projection, generated.boundary(),
				generated.configuration(), generated.modeledFeatures(), generated.meshCount(), generated.warnings(),
				generated.failures(), generated.styles(), generated.metrics());
	}

	private GeneratedModel generateModel(String label, MetricMapProjection projection,
			AxisAlignedRectangleXZ fixedBoundary, List<BuildingFeature> features,
			ModelingConfig config, boolean clipToBounds) {
		long started = System.nanoTime();
		MapDataBuilder builder = new MapDataBuilder();
		List<String> warnings = new ArrayList<>();
		List<FeatureFailure> failures = new ArrayList<>();
		List<StyledBuilding> styles = new ArrayList<>();
		BoundsAccumulator bounds = new BoundsAccumulator();
		int modeledFeatures = 0;
		long ruleNanos = 0;

		for (BuildingFeature feature : features) {
			try {
				long ruleStarted = System.nanoTime();
				StyledBuilding styled = ruleEngine.evaluate(feature, config);
				OsmTagMapper.TagMapping mapping = tagMapper.toTags(styled);
				ruleNanos += System.nanoTime() - ruleStarted;
				TagSet tags = TagSet.of(mapping.tags());
				Geometry geometry = feature.geometryWgs84();
				if (geometry instanceof Polygon polygon) {
					addPolygon(builder, polygon, projection, tags, bounds);
				} else if (geometry instanceof MultiPolygon multiPolygon) {
					for (int i = 0; i < multiPolygon.getNumGeometries(); i++) {
						addPolygon(builder, (Polygon)multiPolygon.getGeometryN(i), projection, tags, bounds);
					}
				} else {
					throw new IllegalArgumentException("Only Polygon and MultiPolygon are modelable");
				}
				styles.add(styled);
				modeledFeatures++;
			} catch (RuntimeException exception) {
				ModelFailureCategory category = exception instanceof IllegalArgumentException
						? ModelFailureCategory.GEOMETRY : ModelFailureCategory.TAG_MAPPING;
				String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
				warnings.add(feature.id() + ": " + message);
				failures.add(new FeatureFailure(feature.id(), category, message));
			}
		}

		if (modeledFeatures == 0 || !bounds.initialized()) {
			return new GeneratedModel(null, null, null, 0, 0, warnings, failures, styles,
					new EngineMetrics(System.nanoTime() - started, ruleNanos, 0,
							Double.NaN, Double.NaN, 0, 0));
		}

		AxisAlignedRectangleXZ boundary = fixedBoundary != null ? fixedBoundary : bounds.toRectangle().pad(1.0);
		O2WConfig osmConfig = configuration(config, clipToBounds);
		long conversionStarted = System.nanoTime();
		try {
			var mapData = builder.build(boundary);
			O2WConverter converter = new O2WConverter();
			converter.setConfig(osmConfig);
			Scene scene = converter.convert(mapData, projection);
			var meshes = scene.getMeshes(osmConfig);
			if (meshes.isEmpty()) throw new IllegalStateException("OSM2World produced no meshes for " + label);
			long vertexCount = meshes.stream()
					.mapToLong(mesh -> mesh.geometry.asTriangles().vertices().size()).sum();
			if (vertexCount % 3 != 0) {
				throw new IllegalStateException("OSM2World produced a non-triangular vertex stream for " + label);
			}
			double minimumY = meshes.stream().flatMap(mesh -> mesh.geometry.asTriangles().vertices().stream())
					.mapToDouble(vertex -> vertex.y).min().orElse(Double.NaN);
			double maximumY = meshes.stream().flatMap(mesh -> mesh.geometry.asTriangles().vertices().stream())
					.mapToDouble(vertex -> vertex.y).max().orElse(Double.NaN);
			long conversionNanos = System.nanoTime() - conversionStarted;
			return new GeneratedModel(scene, boundary, osmConfig, modeledFeatures, meshes.size(), warnings, failures,
					styles, new EngineMetrics(System.nanoTime() - started, ruleNanos, conversionNanos,
							minimumY, maximumY, vertexCount, vertexCount / 3));
		} catch (RuntimeException exception) {
			throw new ModelEngineException(ModelFailureCategory.OSM2WORLD_CONVERSION,
					"OSM2World conversion failed for " + label + ": " + exception.getMessage(), exception);
		}
	}

	private static void addPolygon(MapDataBuilder builder, Polygon polygon, MetricMapProjection projection,
			TagSet tags, BoundsAccumulator bounds) {
		List<MapNode> outer = toNodes(builder, polygon.getExteriorRing().getCoordinates(), projection, bounds);
		List<List<MapNode>> holes = new ArrayList<>(polygon.getNumInteriorRing());
		for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
			holes.add(toNodes(builder, polygon.getInteriorRingN(i).getCoordinates(), projection, bounds));
		}
		if (holes.isEmpty()) builder.createWayArea(outer, tags);
		else builder.createMultipolygonArea(outer, holes, tags);
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

	private static O2WConfig configuration(ModelingConfig config, boolean clipToBounds) {
		return new O2WConfig(Map.of(
				"lod", config.lod(),
				"keepOsmElements", false,
				"clipToBounds", clipToBounds,
				"gltfExtensionWhitelist", "KHR_mesh_quantization",
				"useBuildingColors", true,
				"renderUnderground", false));
	}

	public record FeatureFailure(String featureId, ModelFailureCategory category, String message) {}

	public record EngineMetrics(long totalNanos, long ruleNanos, long conversionNanos,
			double minimumY, double maximumY, long vertexCount, long triangleCount) {}

	public record GeneratedTile(TileNumber tile, Scene scene, MetricMapProjection projection,
			AxisAlignedRectangleXZ boundary, O2WConfig configuration, int modeledFeatures, int meshCount,
			List<String> warnings, List<FeatureFailure> failures, List<StyledBuilding> styles,
			EngineMetrics metrics) {
		static GeneratedTile empty(TileNumber tile) {
			return new GeneratedTile(tile, null, null, null, null, 0, 0, List.of(),
					List.of(), List.of(), new EngineMetrics(0, 0, 0, Double.NaN, Double.NaN, 0, 0));
		}
		public boolean empty() { return scene == null; }
	}

	public record GeneratedRegion(String regionId, Scene scene, MetricMapProjection projection,
			AxisAlignedRectangleXZ boundary, O2WConfig configuration, int modeledFeatures, int meshCount,
			List<String> warnings, List<FeatureFailure> failures, List<StyledBuilding> styles,
			EngineMetrics metrics) {
		public boolean empty() { return scene == null; }
	}

	private record GeneratedModel(Scene scene, AxisAlignedRectangleXZ boundary, O2WConfig configuration,
			int modeledFeatures, int meshCount, List<String> warnings, List<FeatureFailure> failures,
			List<StyledBuilding> styles, EngineMetrics metrics) {}

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
		boolean initialized() { return Double.isFinite(minX); }
		AxisAlignedRectangleXZ toRectangle() { return new AxisAlignedRectangleXZ(minX, minZ, maxX, maxZ); }
	}
}
