package org.osm2world.buildingtiler.tiles;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static org.osm2world.output.common.compression.Compression.NONE;
import static org.osm2world.output.gltf.GltfFlavor.GLB;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.locationtech.jts.geom.Envelope;
import org.osm2world.buildingtiler.application.UpstreamBaseline;
import org.osm2world.buildingtiler.domain.BuildingFeature;
import org.osm2world.buildingtiler.gis.DatasetReadResult;
import org.osm2world.buildingtiler.modeling.OsmTagMapper;
import org.osm2world.buildingtiler.osm2world.Osm2WorldEngineAdapter;
import org.osm2world.math.geo.LatLon;
import org.osm2world.math.geo.LatLonBounds;
import org.osm2world.math.geo.TileNumber;
import org.osm2world.output.tileset.TilesetOutput;
import org.osm2world.output.tileset.TilesetTreeUtil;
import org.osm2world.scene.mesh.LevelOfDetail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class TilesetWriterAdapter {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Comparator<TileNumber> TILE_ORDER = Comparator
			.comparingInt((TileNumber tile) -> tile.zoom)
			.thenComparingInt(tile -> tile.x)
			.thenComparingInt(tile -> tile.y);

	private final Osm2WorldEngineAdapter engineAdapter;
	private final TilesetValidator validator;

	public TilesetWriterAdapter(Osm2WorldEngineAdapter engineAdapter, TilesetValidator validator) {
		this.engineAdapter = engineAdapter;
		this.validator = validator;
	}

	public GenerationResult write(DatasetReadResult dataset, Path outputDirectory,
			int zoom, int lodNumber, int maxTiles) throws IOException {
		return write(dataset, outputDirectory, zoom, lodNumber, maxTiles, false);
	}

	public GenerationResult write(DatasetReadResult dataset, Path outputDirectory,
			int zoom, int lodNumber, int maxTiles, boolean clipToBounds) throws IOException {
		LevelOfDetail lod = LevelOfDetail.fromInt(lodNumber);
		if (lod == null) throw new IOException("LOD must be 0, 1, 2, 3 or 4: " + lodNumber);

		Path output = outputDirectory.toAbsolutePath().normalize();
		if (Files.exists(output)) {
			throw new IOException("Output directory already exists; choose a new directory: " + output);
		}
		if (output.getParent() == null) throw new IOException("Output needs a parent directory: " + output);
		Files.createDirectories(output.getParent());
		Path staging = output.resolveSibling(output.getFileName() + ".staging-" + UUID.randomUUID());
		Files.createDirectories(staging);

		try {
			GenerationResult result = writeStaging(dataset, staging, zoom, lod, maxTiles, clipToBounds);
			try {
				Files.move(staging, output, ATOMIC_MOVE);
			} catch (IOException atomicMoveFailure) {
				Files.move(staging, output, StandardCopyOption.REPLACE_EXISTING);
			}
			return result.withOutputDirectory(output);
		} catch (Exception exception) {
			deleteTree(staging);
			if (exception instanceof IOException ioException) throw ioException;
			throw new IOException("3D Tiles generation failed: " + exception.getMessage(), exception);
		}
	}

	private GenerationResult writeStaging(DatasetReadResult dataset, Path staging,
			int zoom, LevelOfDetail lod, int maxTiles, boolean clipToBounds) throws IOException {

		Map<TileNumber, List<BuildingFeature>> grouped = dataset.buildings().stream()
				.collect(Collectors.groupingBy(feature -> ownerTile(feature, zoom)));
		List<TileGroup> selected = grouped.entrySet().stream()
				.map(entry -> new TileGroup(entry.getKey(), List.copyOf(entry.getValue())))
				.sorted(Comparator.comparingInt((TileGroup group) -> group.features().size()).reversed()
						.thenComparing(TileGroup::tile, TILE_ORDER))
				.limit(maxTiles > 0 ? maxTiles : Long.MAX_VALUE)
				.sorted(Comparator.comparing(TileGroup::tile, TILE_ORDER))
				.toList();
		if (selected.size() < 2) {
			throw new IOException("M0 requires at least two non-empty spatial tiles; found " + selected.size());
		}

		List<TileNumber> writtenTiles = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		int modeledBuildings = 0;
		int meshCount = 0;
		int crossTileBuildings = 0;
		Envelope modeledBounds = new Envelope();

		for (TileGroup group : selected) {
			var generated = engineAdapter.generate(group.tile(), group.features(), lod, clipToBounds);
			Path tilesetFile = TilesetTreeUtil.tilePath(staging, group.tile(), lod, ".tileset.json");
			Files.createDirectories(tilesetFile.getParent());

			TilesetOutput output = new TilesetOutput(tilesetFile.toFile(), GLB, NONE,
					generated.projection(), generated.boundary());
			output.setConfiguration(generated.configuration());
			output.outputScene(generated.scene());

			Path glbFile = TilesetTreeUtil.tilePath(staging, group.tile(), lod, ".glb");
			if (!Files.isRegularFile(tilesetFile) || !Files.isRegularFile(glbFile)) {
				throw new IOException("OSM2World did not write both tile files for " + group.tile());
			}
			writtenTiles.add(group.tile());
			modeledBuildings += generated.modeledFeatures();
			meshCount += generated.meshCount();
			warnings.addAll(generated.warnings());
			for (BuildingFeature feature : group.features()) {
				Envelope envelope = feature.geometryWgs84().getEnvelopeInternal();
				modeledBounds.expandToInclude(envelope);
				if (spansMultipleTiles(envelope, zoom)) crossTileBuildings++;
			}
		}

		TilesetTreeUtil.generateTilesetTree(staging, writtenTiles, List.of(lod));
		TilesetValidator.ValidationResult validation = validator.validate(staging);
		if (!validation.valid()) {
			throw new IOException("Generated tileset failed validation: " + validation.errors());
		}

		long outputBytes = directoryBytes(staging);
		Map<String, Object> manifest = manifest(dataset, modeledBounds, zoom, lod, writtenTiles, clipToBounds);
		Map<String, Object> report = report(dataset, grouped.size(), modeledBuildings, meshCount,
				crossTileBuildings, clipToBounds,
				writtenTiles, lod, outputBytes, warnings, validation);
		writeJson(staging.resolve("manifest.json"), manifest);
		writeJson(staging.resolve("generation-report.json"), report);

		return new GenerationResult(staging, dataset.metadata().featureCount(), modeledBuildings,
				writtenTiles.size(), meshCount, List.of(lod.ordinal()), outputBytes,
				List.copyOf(warnings), validation, boundsArray(modeledBounds),
				writtenTiles.stream().map(TileNumber::toString).toList());
	}

	private static TileNumber ownerTile(BuildingFeature feature, int zoom) {
		var centroid = feature.geometryWgs84().getCentroid().getCoordinate();
		return TileNumber.atLatLon(zoom, new LatLon(centroid.y, centroid.x));
	}

	private static boolean spansMultipleTiles(Envelope envelope, int zoom) {
		LatLonBounds bounds = new LatLonBounds(
				new LatLon(envelope.getMinY(), envelope.getMinX()),
				new LatLon(envelope.getMaxY(), envelope.getMaxX()));
		return TileNumber.tilesForBounds(zoom, bounds).size() > 1;
	}

	private static Map<String, Object> manifest(DatasetReadResult dataset, Envelope modeledBounds,
			int zoom, LevelOfDetail lod, List<TileNumber> tiles, boolean clipToBounds) {
		Map<String, Object> manifest = new LinkedHashMap<>();
		manifest.put("applicationVersion", UpstreamBaseline.APPLICATION_VERSION);
		manifest.put("osm2worldVersion", UpstreamBaseline.OSM2WORLD_VERSION);
		manifest.put("osm2worldCommit", UpstreamBaseline.OSM2WORLD_COMMIT);
		manifest.put("ruleEngineVersion", OsmTagMapper.RULE_VERSION);
		manifest.put("sourceFormat", dataset.metadata().format());
		manifest.put("sourceCrs", dataset.metadata().sourceCrs());
		manifest.put("sourceEncoding", dataset.metadata().sourceEncoding());
		manifest.put("heightField", "Elevation");
		manifest.put("heightUnit", "m");
		manifest.put("zoom", zoom);
		manifest.put("lods", List.of(lod.ordinal()));
		manifest.put("outputFormats", List.of("3DTILES"));
		manifest.put("clipToBounds", clipToBounds);
		manifest.put("boundsWgs84", boundsArray(modeledBounds));
		manifest.put("sourceBoundsWgs84", boundsArray(dataset.metadata().boundsWgs84()));
		manifest.put("tiles", tiles.stream().map(TileNumber::toString).toList());
		manifest.put("buildTime", Instant.now().toString());
		return manifest;
	}

	private static Map<String, Object> report(DatasetReadResult dataset, int availableTileCount,
			int modeledBuildings, int meshCount, int crossTileBuildings, boolean clipToBounds,
			List<TileNumber> tiles, LevelOfDetail lod,
			long outputBytes, List<String> warnings, TilesetValidator.ValidationResult validation) {
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("inputFeatures", dataset.metadata().featureCount());
		report.put("validBuildings", dataset.metadata().validBuildings());
		report.put("skippedInvalidHeight", dataset.metadata().skippedInvalidHeight());
		report.put("skippedInvalidGeometry", dataset.metadata().skippedInvalidGeometry());
		report.put("availableTileCount", availableTileCount);
		report.put("modeledBuildings", modeledBuildings);
		report.put("tileCount", tiles.size());
		report.put("meshCount", meshCount);
		report.put("crossTileBuildings", crossTileBuildings);
		report.put("crossTileStrategy", clipToBounds
				? "centroid-owner, clipped to owner tile"
				: "centroid-owner, unclipped full geometry");
		report.put("failedTiles", 0);
		report.put("lods", List.of(lod.ordinal()));
		report.put("outputBytesBeforeReports", outputBytes);
		report.put("warnings", warnings);
		report.put("validation", validation);
		return report;
	}

	private static List<Double> boundsArray(Envelope envelope) {
		return List.of(envelope.getMinX(), envelope.getMinY(), envelope.getMaxX(), envelope.getMaxY());
	}

	private static long directoryBytes(Path root) throws IOException {
		try (var files = Files.walk(root)) {
			return files.filter(Files::isRegularFile).mapToLong(path -> {
				try { return Files.size(path); }
				catch (IOException exception) { throw new IllegalStateException(exception); }
			}).sum();
		}
	}

	private static void writeJson(Path file, Object value) throws IOException {
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(value, writer);
		}
	}

	private static void deleteTree(Path root) {
		if (root == null || !Files.exists(root)) return;
		try {
			Files.walkFileTree(root, new SimpleFileVisitor<>() {
				@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.deleteIfExists(file);
					return FileVisitResult.CONTINUE;
				}
				@Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
					Files.deleteIfExists(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException ignored) {
			// Best-effort cleanup; the original generation failure remains the primary error.
		}
	}

	private record TileGroup(TileNumber tile, List<BuildingFeature> features) {}

	public record GenerationResult(
			Path outputDirectory,
			long inputFeatures,
			int modeledBuildings,
			int tileCount,
			int meshCount,
			List<Integer> lods,
			long outputBytesBeforeReports,
			List<String> warnings,
			TilesetValidator.ValidationResult validation,
			List<Double> boundsWgs84,
			List<String> tiles) {

		GenerationResult withOutputDirectory(Path outputDirectory) {
			return new GenerationResult(outputDirectory, inputFeatures, modeledBuildings, tileCount,
					meshCount, lods, outputBytesBeforeReports, warnings, validation, boundsWgs84, tiles);
		}
	}

}
