package org.osm2world.buildingtiler.tiles;

import static org.osm2world.output.common.compression.Compression.NONE;
import static org.osm2world.output.gltf.GltfFlavor.GLB;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.domain.BuildingFeature;
import org.osm2world.buildingtiler.modeling.StableStyleHash;
import org.osm2world.buildingtiler.osm2world.ModelEngineException;
import org.osm2world.buildingtiler.osm2world.ModelFailureCategory;
import org.osm2world.buildingtiler.osm2world.ModelingLedgerEntry;
import org.osm2world.buildingtiler.osm2world.ModelingLedgerEntry.Stage;
import org.osm2world.buildingtiler.osm2world.ModelingLedgerEntry.Status;
import org.osm2world.buildingtiler.osm2world.Osm2WorldEngineAdapter;
import org.osm2world.output.tileset.TilesetOutput;
import org.osm2world.output.tileset.TilesetTreeUtil;
import org.osm2world.math.geo.MapProjection;
import org.osm2world.math.shapes.AxisAlignedRectangleXZ;
import org.osm2world.scene.mesh.LevelOfDetail;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

public final class Osm2WorldTileRenderer implements TileRenderer {

	private final Osm2WorldEngineAdapter engine;
	private final TileOutputFactory outputFactory;

	public Osm2WorldTileRenderer(Osm2WorldEngineAdapter engine) {
		this(engine, (file, projection, bounds) -> new TilesetOutput(file.toFile(), GLB, NONE,
				projection, bounds));
	}

	Osm2WorldTileRenderer(Osm2WorldEngineAdapter engine, TileOutputFactory outputFactory) {
		this.engine = engine;
		this.outputFactory = outputFactory;
	}

	@Override
	public TileRenderResult render(TileWork work, List<Integer> lods, ModelingConfig config,
			Path stagingDirectory, BooleanSupplier cancelled) throws TileRenderException {
		return renderInternal(work, lods, config, stagingDirectory, cancelled, true);
	}

	private TileRenderResult renderInternal(TileWork work, List<Integer> lods, ModelingConfig config,
			Path stagingDirectory, BooleanSupplier cancelled, boolean allowExportIsolation)
			throws TileRenderException {
		long started = System.nanoTime();
		int modeled = 0;
		int meshes = 0;
		long vertices = 0;
		long triangles = 0;
		long bytes = 0;
		List<String> warnings = new ArrayList<>();
		List<Osm2WorldEngineAdapter.FeatureFailure> failures = new ArrayList<>();
		List<TileContentArtifact> contents = new ArrayList<>();
		List<ModelingLedgerEntry> ledger = null;
		Set<String> expectedPartIds = null;
		Set<String> presentAtEveryLod = null;
		try {
			for (int lodNumber : lods) {
				checkCancellation(cancelled);
				LevelOfDetail lod = LevelOfDetail.fromInt(lodNumber);
				if (lod == null) throw new TileRenderException(TileFailureCategory.DATA,
						"Unsupported LOD " + lodNumber + " for tile " + work.tile());
				var generated = engine.generate(work.tile(), work.features(), config.withLod(lodNumber), false);
				if (generated.empty()) throw new TileRenderException(TileFailureCategory.GEOMETRY,
						"No modelable feature remained in tile " + work.tile());
				Path tilesetFile = TilesetTreeUtil.tilePath(stagingDirectory, work.tile(), lod, ".tileset.json");
				Path glbFile = TilesetTreeUtil.tilePath(stagingDirectory, work.tile(), lod, ".glb");
				Files.createDirectories(tilesetFile.getParent());
				TilesetOutput output = outputFactory.create(tilesetFile, generated.projection(), generated.boundary());
				output.setConfiguration(generated.configuration());
				output.outputScene(generated.scene());
				if (!Files.isRegularFile(tilesetFile) || !Files.isRegularFile(glbFile)) {
					throw new IOException("OSM2World did not write both content files");
				}
				new TilesetRegionReconciler().expandToFinalVertices(tilesetFile, glbFile);
				Set<String> generatedExpected = generated.ledger().stream()
						.filter(entry -> entry.status() == Status.PENDING)
						.map(ModelingLedgerEntry::partId)
						.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
				if (ledger == null) {
					ledger = new ArrayList<>(generated.ledger());
					expectedPartIds = generatedExpected;
				} else if (!expectedPartIds.equals(generatedExpected)) {
					throw new IOException("MODELING_LEDGER_UNSTABLE: expected part ids differ between LOD exports");
				}
				FinalGlbFeatureIndex finalIndex = new FinalGlbFeatureIndex();
				Set<String> exportedPartIds = finalIndex.readPartIds(glbFile);
				finalIndex.verifyPartHeights(glbFile, generated.ledger().stream()
						.filter(entry -> entry.status() == Status.PENDING)
						.collect(java.util.stream.Collectors.toMap(ModelingLedgerEntry::partId,
								ModelingLedgerEntry::expectedHeightMeters)));
				finalIndex.verifyOpenHoles(glbFile,
						expectedHoles(work.features(), generated.projection(), generatedExpected));
				Set<String> unexpected = new LinkedHashSet<>(exportedPartIds);
				unexpected.removeAll(expectedPartIds);
				if (!unexpected.isEmpty()) {
					throw new IOException("FINAL_GLTF_UNEXPECTED_PART: " + unexpected.iterator().next());
				}
				if (presentAtEveryLod == null) presentAtEveryLod = new LinkedHashSet<>(exportedPartIds);
				else presentAtEveryLod.retainAll(exportedPartIds);
				meshes += generated.meshCount();
				vertices += generated.metrics().vertexCount();
				triangles += generated.metrics().triangleCount();
				bytes += Files.size(tilesetFile) + Files.size(glbFile);
				contents.add(new TileContentArtifact(work.tile().toString(), lodNumber,
						portable(stagingDirectory.relativize(tilesetFile)),
						portable(stagingDirectory.relativize(glbFile))));
				warnings.addAll(generated.warnings());
				failures.addAll(generated.failures());
			}
			checkCancellation(cancelled);
			Set<String> finalParts = presentAtEveryLod == null ? Set.of() : Set.copyOf(presentAtEveryLod);
			List<ModelingLedgerEntry> finalLedger = ledger == null ? List.of() : ledger.stream().map(entry -> {
				if (entry.status() != Status.PENDING) return entry;
				if (finalParts.contains(entry.partId())) {
					return entry.transition(Stage.FINAL_GLTF, Status.MODELED, "MODELED", "Final GLB part metadata matched");
				}
				return entry.transition(Stage.FINAL_GLTF, Status.MISSING_UNATTRIBUTED,
						"MISSING_UNATTRIBUTED", "Expected part is absent from final GLB metadata");
			}).toList();
			for (ModelingLedgerEntry entry : finalLedger) if (entry.status() == Status.MISSING_UNATTRIBUTED) {
				failures.add(new Osm2WorldEngineAdapter.FeatureFailure(entry.partId(),
						ModelFailureCategory.GLTF_EXPORT, entry.message()));
			}
			int modeledParts = (int)finalLedger.stream().filter(entry -> entry.status() == Status.MODELED).count();
			modeled = (int)finalLedger.stream().collect(java.util.stream.Collectors.groupingBy(
					ModelingLedgerEntry::sourceFeatureId)).values().stream()
					.filter(entries -> entries.stream().allMatch(entry -> entry.status() == Status.MODELED)).count();
			return new TileRenderResult(work.tile().toString(), modeled, modeledParts, meshes, vertices, triangles,
					bytes, System.nanoTime() - started, contents, warnings, failures, finalLedger);
		} catch (CancellationException exception) {
			cleanup(work, lods, stagingDirectory);
			throw exception;
		} catch (TileRenderException exception) {
			cleanup(work, lods, stagingDirectory);
			throw exception;
		} catch (IOException exception) {
			cleanup(work, lods, stagingDirectory);
			throw new TileRenderException(TileFailureCategory.IO_TRANSIENT,
					"I/O failure for tile " + work.tile() + ": " + exception.getMessage(), exception);
		} catch (ModelEngineException exception) {
			cleanup(work, lods, stagingDirectory);
			throw new TileRenderException(TileFailureCategory.INTERNAL,
					"OSM2World failure for tile " + work.tile() + ": " + exception.getMessage(), exception);
		} catch (RuntimeException exception) {
			cleanup(work, lods, stagingDirectory);
			if (allowExportIsolation && work.features().size() > 1) {
				return isolateExportFailure(work, lods, config, stagingDirectory, cancelled, exception);
			}
			throw new TileRenderException(TileFailureCategory.INTERNAL,
					"Unexpected failure for tile " + work.tile() + ": " + exception.getMessage(), exception);
		}
	}

	private TileRenderResult isolateExportFailure(TileWork work, List<Integer> lods, ModelingConfig config,
			Path stagingDirectory, BooleanSupplier cancelled, RuntimeException original)
			throws TileRenderException {
		List<BuildingFeature> accepted = new ArrayList<>();
		List<BuildingFeature> rejected = new ArrayList<>();
		for (BuildingFeature candidate : work.features()) {
			List<BuildingFeature> probe = new ArrayList<>(accepted);
			probe.add(candidate);
			try {
				renderInternal(new TileWork(work.tile(), probe), lods, config,
						stagingDirectory, cancelled, false);
				accepted.add(candidate);
			} catch (TileRenderException exception) {
				rejected.add(candidate);
			}
		}
		if (accepted.isEmpty() || rejected.isEmpty()) {
			throw new TileRenderException(TileFailureCategory.INTERNAL,
					"Unexpected export failure for tile " + work.tile() + ": " + original.getMessage(), original);
		}

		// The last failed probe may have removed the prior good files, so always emit
		// the final accepted aggregate once more before returning it.
		TileRenderResult rendered = renderInternal(new TileWork(work.tile(), accepted), lods, config,
				stagingDirectory, cancelled, false);
		List<ModelingLedgerEntry> ledger = new ArrayList<>(rendered.modelingLedger());
		List<Osm2WorldEngineAdapter.FeatureFailure> failures = new ArrayList<>(rendered.featureFailures());
		List<String> warnings = new ArrayList<>(rendered.warnings());
		String styleHash = new StableStyleHash().configHash(config);
		String message = "Final glTF export failed when this feature was included: "
				+ (original.getMessage() == null ? original.getClass().getSimpleName() : original.getMessage());
		for (BuildingFeature feature : rejected) {
			failures.add(new Osm2WorldEngineAdapter.FeatureFailure(feature.id(),
					ModelFailureCategory.GLTF_EXPORT, message));
			warnings.add(feature.id() + ": " + message);
			List<Polygon> parts = polygonParts(feature.geometryWgs84());
			for (int partIndex = 0; partIndex < Math.max(1, parts.size()); partIndex++) {
				Polygon part = parts.isEmpty() ? null : parts.get(partIndex);
				ledger.add(new ModelingLedgerEntry(feature.id(), feature.id() + "/part/" + partIndex,
						partIndex, part == null ? 0 : part.getNumInteriorRing(), work.tile().toString(),
						feature.heightMeters(), styleHash, Stage.GLTF_EXPORT,
						Status.FAILED_GLTF_EXPORT, "FAILED_GLTF_EXPORT", message));
			}
		}
		return new TileRenderResult(rendered.tile(), rendered.modeledBuildings(), rendered.modeledParts(),
				rendered.meshCount(), rendered.vertexCount(), rendered.triangleCount(), rendered.outputBytes(),
				rendered.elapsedNanos(), rendered.contents(), warnings, failures, ledger);
	}

	private static List<Polygon> polygonParts(Geometry geometry) {
		if (geometry instanceof Polygon polygon) return List.of(polygon);
		if (geometry instanceof MultiPolygon multiPolygon) {
			List<Polygon> parts = new ArrayList<>(multiPolygon.getNumGeometries());
			for (int index = 0; index < multiPolygon.getNumGeometries(); index++) {
				parts.add((Polygon)multiPolygon.getGeometryN(index));
			}
			return parts;
		}
		return List.of();
	}

	private static Map<String, List<FinalGlbFeatureIndex.HorizontalPoint>> expectedHoles(
			List<BuildingFeature> features, MapProjection projection, Set<String> expectedPartIds) {
		Map<String, List<FinalGlbFeatureIndex.HorizontalPoint>> result = new LinkedHashMap<>();
		for (BuildingFeature feature : features) {
			List<Polygon> parts = polygonParts(feature.geometryWgs84());
			for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
				String partId = feature.id() + "/part/" + partIndex;
				Polygon part = parts.get(partIndex);
				if (!expectedPartIds.contains(partId) || part.getNumInteriorRing() == 0) continue;
				List<FinalGlbFeatureIndex.HorizontalPoint> points = new ArrayList<>();
				for (int holeIndex = 0; holeIndex < part.getNumInteriorRing(); holeIndex++) {
					var hole = part.getFactory().createPolygon(part.getInteriorRingN(holeIndex).getCoordinates());
					var coordinate = hole.getInteriorPoint().getCoordinate();
					var local = projection.toXZ(coordinate.y, coordinate.x);
					// GltfOutput writes local OSM2World (x, y, z) as glTF (x, y, -z).
					points.add(new FinalGlbFeatureIndex.HorizontalPoint(local.x, -local.z));
				}
				result.put(partId, List.copyOf(points));
			}
		}
		return Map.copyOf(result);
	}

	private static String portable(Path relative) {
		return relative.toString().replace('\\', '/');
	}

	private static void checkCancellation(BooleanSupplier cancelled) {
		if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
			throw new CancellationException("Tile rendering cancelled");
		}
	}

	private static void cleanup(TileWork work, List<Integer> lods, Path stagingDirectory) {
		for (int lodNumber : lods) {
			LevelOfDetail lod = LevelOfDetail.fromInt(lodNumber);
			if (lod == null) continue;
			try {
				Files.deleteIfExists(TilesetTreeUtil.tilePath(stagingDirectory, work.tile(), lod, ".tileset.json"));
				Files.deleteIfExists(TilesetTreeUtil.tilePath(stagingDirectory, work.tile(), lod, ".glb"));
			} catch (IOException ignored) {
				// The job diagnostics cleanup handles a file that remains locked on Windows.
			}
		}
	}

	@FunctionalInterface
	interface TileOutputFactory {
		TilesetOutput create(Path tilesetFile, MapProjection projection, AxisAlignedRectangleXZ bounds);
	}
}
