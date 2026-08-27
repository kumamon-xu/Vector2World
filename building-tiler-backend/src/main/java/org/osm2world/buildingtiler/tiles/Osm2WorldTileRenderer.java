package org.osm2world.buildingtiler.tiles;

import static org.osm2world.output.common.compression.Compression.NONE;
import static org.osm2world.output.gltf.GltfFlavor.GLB;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.osm2world.ModelEngineException;
import org.osm2world.buildingtiler.osm2world.Osm2WorldEngineAdapter;
import org.osm2world.output.tileset.TilesetOutput;
import org.osm2world.output.tileset.TilesetTreeUtil;
import org.osm2world.scene.mesh.LevelOfDetail;

public final class Osm2WorldTileRenderer implements TileRenderer {

	private final Osm2WorldEngineAdapter engine;

	public Osm2WorldTileRenderer(Osm2WorldEngineAdapter engine) {
		this.engine = engine;
	}

	@Override
	public TileRenderResult render(TileWork work, List<Integer> lods, ModelingConfig config,
			Path stagingDirectory, BooleanSupplier cancelled) throws TileRenderException {
		long started = System.nanoTime();
		int modeled = 0;
		int meshes = 0;
		long vertices = 0;
		long triangles = 0;
		long bytes = 0;
		List<String> warnings = new ArrayList<>();
		List<Osm2WorldEngineAdapter.FeatureFailure> failures = new ArrayList<>();
		List<TileContentArtifact> contents = new ArrayList<>();
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
				TilesetOutput output = new TilesetOutput(tilesetFile.toFile(), GLB, NONE,
						generated.projection(), generated.boundary());
				output.setConfiguration(generated.configuration());
				output.outputScene(generated.scene());
				if (!Files.isRegularFile(tilesetFile) || !Files.isRegularFile(glbFile)) {
					throw new IOException("OSM2World did not write both content files");
				}
				modeled = Math.max(modeled, generated.modeledFeatures());
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
			return new TileRenderResult(work.tile().toString(), modeled, meshes, vertices, triangles,
					bytes, System.nanoTime() - started, contents, warnings, failures);
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
			throw new TileRenderException(TileFailureCategory.INTERNAL,
					"Unexpected failure for tile " + work.tile() + ": " + exception.getMessage(), exception);
		}
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
}
