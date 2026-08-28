package org.osm2world.buildingtiler.osm2world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

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
import org.osm2world.buildingtiler.osm2world.ModelingLedgerEntry.Stage;
import org.osm2world.buildingtiler.osm2world.ModelingLedgerEntry.Status;
import org.osm2world.buildingtiler.tiles.FinalGlbFeatureIndex;
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
	private final Osm2WorldConfigFactory configFactory;
	private final Supplier<O2WConverter> converterFactory;

	public Osm2WorldEngineAdapter(OsmTagMapper tagMapper) {
		this(tagMapper, new BuildingRuleEngine());
	}

	public Osm2WorldEngineAdapter(OsmTagMapper tagMapper, BuildingRuleEngine ruleEngine) {
		this(tagMapper, ruleEngine, new Osm2WorldConfigFactory());
	}

	public Osm2WorldEngineAdapter(OsmTagMapper tagMapper, BuildingRuleEngine ruleEngine,
			Osm2WorldConfigFactory configFactory) {
		this(tagMapper, ruleEngine, configFactory, O2WConverter::new);
	}

	Osm2WorldEngineAdapter(OsmTagMapper tagMapper, BuildingRuleEngine ruleEngine,
			Osm2WorldConfigFactory configFactory, Supplier<O2WConverter> converterFactory) {
		this.tagMapper = tagMapper;
		this.ruleEngine = ruleEngine;
		this.configFactory = configFactory;
		this.converterFactory = converterFactory;
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
		GeneratedModel generated = generateModelIsolating(tile.toString(), projection, fixedBoundary,
				features, config, clipToBounds);
		return new GeneratedTile(tile, generated.scene(), projection, generated.boundary(), generated.configuration(),
				generated.modeledFeatures(), generated.meshCount(), generated.warnings(), generated.failures(),
				generated.styles(), generated.ledger(), generated.metrics());
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
		GeneratedModel generated = generateModelIsolating(regionId, projection, null, features, config, false);
		return new GeneratedRegion(regionId, generated.scene(), projection, generated.boundary(),
				generated.configuration(), generated.modeledFeatures(), generated.meshCount(), generated.warnings(),
				generated.failures(), generated.styles(), generated.ledger(), generated.metrics());
	}

	private GeneratedModel generateModelIsolating(String label, MetricMapProjection projection,
			AxisAlignedRectangleXZ fixedBoundary, List<BuildingFeature> features,
			ModelingConfig config, boolean clipToBounds) {
		long started = System.nanoTime();
		try {
			return generateModel(label, projection, fixedBoundary, features, config, clipToBounds);
		} catch (ModelEngineException initialFailure) {
			// Rebuild incrementally only on the exceptional path. A feature which makes
			// conversion fail is rejected while the last known-good aggregate remains usable.
			List<BuildingFeature> accepted = new ArrayList<>();
			List<ModelingLedgerEntry> rejectedLedger = new ArrayList<>();
			List<FeatureFailure> rejectedFailures = new ArrayList<>();
			List<String> rejectedWarnings = new ArrayList<>();
			GeneratedModel current = null;
			for (BuildingFeature feature : features) {
				checkCancellation();
				List<BuildingFeature> probeFeatures = new ArrayList<>(accepted);
				probeFeatures.add(feature);
				try {
					GeneratedModel probe = generateModel(label, projection, fixedBoundary,
							probeFeatures, config, clipToBounds);
					boolean candidateAccepted = probe.ledger().stream()
							.anyMatch(entry -> entry.sourceFeatureId().equals(feature.id())
									&& entry.status() == Status.PENDING);
					if (candidateAccepted) {
						accepted.add(feature);
						current = probe;
					} else {
						probe.ledger().stream().filter(entry -> entry.sourceFeatureId().equals(feature.id()))
								.forEach(rejectedLedger::add);
						probe.failures().stream().filter(failure -> belongsTo(failure.featureId(), feature.id()))
								.forEach(rejectedFailures::add);
					}
				} catch (ModelEngineException featureFailure) {
					String message = featureFailure.category().name() + ": "
							+ (featureFailure.getMessage() == null ? "conversion failed" : featureFailure.getMessage());
					rejectedWarnings.add(feature.id() + ": " + message);
					rejectedFailures.add(new FeatureFailure(feature.id(),
							ModelFailureCategory.OSM2WORLD_CONVERSION, message));
					String styleHash = "";
					try { styleHash = ruleEngine.evaluate(feature, config).style().outputHash(); }
					catch (RuntimeException ignored) { /* the original rule failure is reported elsewhere */ }
					List<Polygon> parts = polygonParts(feature.geometryWgs84());
					for (int partIndex = 0; partIndex < Math.max(1, parts.size()); partIndex++) {
						Polygon part = parts.isEmpty() ? null : parts.get(partIndex);
						rejectedLedger.add(new ModelingLedgerEntry(feature.id(), partId(feature.id(), partIndex),
								partIndex, part == null ? 0 : part.getNumInteriorRing(), label,
								feature.heightMeters(), styleHash, Stage.O2W_CONVERSION,
								Status.FAILED_O2W_CONVERSION, "FAILED_O2W_CONVERSION", message));
					}
				}
			}
			if (current == null) {
				return new GeneratedModel(null, null, null, 0, 0, rejectedWarnings, rejectedFailures,
						List.of(), rejectedLedger, new EngineMetrics(System.nanoTime() - started,
								0, 0, Double.NaN, Double.NaN, 0, 0));
			}
			List<String> warnings = new ArrayList<>(current.warnings());
			warnings.addAll(rejectedWarnings);
			List<FeatureFailure> failures = new ArrayList<>(current.failures());
			failures.addAll(rejectedFailures);
			List<ModelingLedgerEntry> ledger = new ArrayList<>(current.ledger());
			ledger.addAll(rejectedLedger);
			EngineMetrics metrics = current.metrics();
			return new GeneratedModel(current.scene(), current.boundary(), current.configuration(),
					current.modeledFeatures(), current.meshCount(), warnings, failures, current.styles(), ledger,
					new EngineMetrics(System.nanoTime() - started, metrics.ruleNanos(), metrics.conversionNanos(),
							metrics.minimumY(), metrics.maximumY(), metrics.vertexCount(), metrics.triangleCount()));
		}
	}

	private static boolean belongsTo(String failureId, String featureId) {
		return failureId.equals(featureId) || failureId.startsWith(featureId + "/part/");
	}

	private GeneratedModel generateModel(String label, MetricMapProjection projection,
			AxisAlignedRectangleXZ fixedBoundary, List<BuildingFeature> features,
			ModelingConfig config, boolean clipToBounds) {
		long started = System.nanoTime();
		MapDataBuilder builder = new MapDataBuilder();
		List<String> warnings = new ArrayList<>();
		List<FeatureFailure> failures = new ArrayList<>();
		List<StyledBuilding> styles = new ArrayList<>();
		List<ModelingLedgerEntry> ledger = new ArrayList<>();
		BoundsAccumulator bounds = new BoundsAccumulator();
		int modeledFeatures = 0;
		long ruleNanos = 0;

		for (BuildingFeature feature : features) {
			checkCancellation();
			List<Polygon> parts = polygonParts(feature.geometryWgs84());
			StyledBuilding styled;
			OsmTagMapper.TagMapping mapping;
			try {
				long ruleStarted = System.nanoTime();
				styled = ruleEngine.evaluate(feature, config);
				mapping = tagMapper.toTags(styled);
				ruleNanos += System.nanoTime() - ruleStarted;
			} catch (RuntimeException exception) {
				ModelFailureCategory category = exception instanceof IllegalArgumentException
						? ModelFailureCategory.GEOMETRY : ModelFailureCategory.TAG_MAPPING;
				String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
				warnings.add(feature.id() + ": " + message);
				failures.add(new FeatureFailure(feature.id(), category, message));
				for (int partIndex = 0; partIndex < Math.max(1, parts.size()); partIndex++) {
					Polygon part = parts.isEmpty() ? null : parts.get(partIndex);
					ledger.add(new ModelingLedgerEntry(feature.id(), partId(feature.id(), partIndex), partIndex,
							part == null ? 0 : part.getNumInteriorRing(), label, feature.heightMeters(), "",
							Stage.RULE, Status.FAILED_RULE, "FAILED_RULE", message));
				}
				continue;
			}

			boolean acceptedAnyPart = false;
			for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
				Polygon part = parts.get(partIndex);
				String partId = partId(feature.id(), partIndex);
				try {
					Map<String, String> partTags = new LinkedHashMap<>(mapping.tags());
					partTags.put(FinalGlbFeatureIndex.partTag(), partId);
					addPolygon(builder, part, projection, TagSet.of(partTags), bounds);
					ledger.add(new ModelingLedgerEntry(feature.id(), partId, partIndex,
							part.getNumInteriorRing(), label, feature.heightMeters(), styled.style().outputHash(),
							Stage.MAP_BUILD, Status.PENDING, "", ""));
					acceptedAnyPart = true;
				} catch (RuntimeException exception) {
					String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
					warnings.add(partId + ": " + message);
					failures.add(new FeatureFailure(partId, ModelFailureCategory.GEOMETRY, message));
					ledger.add(new ModelingLedgerEntry(feature.id(), partId, partIndex,
							part.getNumInteriorRing(), label, feature.heightMeters(), styled.style().outputHash(),
							Stage.MAP_BUILD, Status.FAILED_MAP_BUILD, "FAILED_MAP_BUILD", message));
				}
			}
			if (acceptedAnyPart) {
				styles.add(styled);
				modeledFeatures++;
			}
		}

		if (modeledFeatures == 0 || !bounds.initialized()) {
			return new GeneratedModel(null, null, null, 0, 0, warnings, failures, styles, ledger,
					new EngineMetrics(System.nanoTime() - started, ruleNanos, 0,
							Double.NaN, Double.NaN, 0, 0));
		}

		AxisAlignedRectangleXZ boundary = fixedBoundary != null ? fixedBoundary : bounds.toRectangle().pad(1.0);
		O2WConverter converter = converterFactory.get();
		O2WConfig osmConfig = configFactory.create(config, clipToBounds);
		long conversionStarted = System.nanoTime();
		try {
			checkCancellation();
			var mapData = builder.build(boundary);
			converter.setConfig(osmConfig);
			Scene scene = converter.convert(mapData, projection);
			checkCancellation();
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
			ledger.replaceAll(entry -> entry.status() == Status.PENDING
					? entry.transition(Stage.O2W_CONVERSION, Status.PENDING, "", "") : entry);
			return new GeneratedModel(scene, boundary, osmConfig, modeledFeatures, meshes.size(), warnings, failures,
					styles, ledger, new EngineMetrics(System.nanoTime() - started, ruleNanos, conversionNanos,
							minimumY, maximumY, vertexCount, vertexCount / 3));
		} catch (CancellationException exception) {
			throw exception;
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

	private static List<Polygon> polygonParts(Geometry geometry) {
		if (geometry instanceof Polygon polygon) return List.of(polygon);
		if (geometry instanceof MultiPolygon multiPolygon) {
			List<Polygon> result = new ArrayList<>(multiPolygon.getNumGeometries());
			for (int index = 0; index < multiPolygon.getNumGeometries(); index++) {
				result.add((Polygon)multiPolygon.getGeometryN(index));
			}
			return List.copyOf(result);
		}
		return List.of();
	}

	private static String partId(String featureId, int partIndex) {
		return featureId + "/part/" + partIndex;
	}

	private static void checkCancellation() {
		if (Thread.currentThread().isInterrupted()) {
			throw new CancellationException("OSM2World conversion cancelled");
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

	public Osm2WorldConfigFactory.BundleInfo styleBundleInfo() { return configFactory.bundleInfo(); }

	public record FeatureFailure(String featureId, ModelFailureCategory category, String message) {}

	public record EngineMetrics(long totalNanos, long ruleNanos, long conversionNanos,
			double minimumY, double maximumY, long vertexCount, long triangleCount) {}

	public record GeneratedTile(TileNumber tile, Scene scene, MetricMapProjection projection,
			AxisAlignedRectangleXZ boundary, O2WConfig configuration, int modeledFeatures, int meshCount,
			List<String> warnings, List<FeatureFailure> failures, List<StyledBuilding> styles,
			List<ModelingLedgerEntry> ledger, EngineMetrics metrics) {
		static GeneratedTile empty(TileNumber tile) {
			return new GeneratedTile(tile, null, null, null, null, 0, 0, List.of(),
					List.of(), List.of(), List.of(), new EngineMetrics(0, 0, 0, Double.NaN, Double.NaN, 0, 0));
		}
		public boolean empty() { return scene == null; }
	}

	public record GeneratedRegion(String regionId, Scene scene, MetricMapProjection projection,
			AxisAlignedRectangleXZ boundary, O2WConfig configuration, int modeledFeatures, int meshCount,
			List<String> warnings, List<FeatureFailure> failures, List<StyledBuilding> styles,
			List<ModelingLedgerEntry> ledger, EngineMetrics metrics) {
		public boolean empty() { return scene == null; }
	}

	private record GeneratedModel(Scene scene, AxisAlignedRectangleXZ boundary, O2WConfig configuration,
			int modeledFeatures, int meshCount, List<String> warnings, List<FeatureFailure> failures,
			List<StyledBuilding> styles, List<ModelingLedgerEntry> ledger, EngineMetrics metrics) {}

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
