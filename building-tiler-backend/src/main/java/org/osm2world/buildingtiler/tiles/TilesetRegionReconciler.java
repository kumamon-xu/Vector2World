package org.osm2world.buildingtiler.tiles;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Expands an OSM2World-generated region to the actual final GLB vertices. A single local tangent
 * plane accumulates measurable ellipsoid curvature over a wide preview/large tile, so footprint
 * bounds and nominal model height alone are not a valid 3D Tiles bounding volume.
 */
final class TilesetRegionReconciler {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	void expandToFinalVertices(Path tilesetFile, Path glbFile) throws IOException {
		Path directory = tilesetFile.toAbsolutePath().normalize().getParent();
		JsonObject tileset;
		try (var reader = Files.newBufferedReader(tilesetFile, UTF_8)) {
			tileset = JsonParser.parseReader(reader).getAsJsonObject();
		} catch (RuntimeException exception) {
			throw new IOException("Cannot reconcile invalid tileset JSON: " + exception.getMessage(), exception);
		}
		JsonObject root = requireObject(tileset, "root");
		JsonObject volume = requireObject(root, "boundingVolume");
		JsonArray region = volume.has("region") && volume.get("region").isJsonArray()
				? volume.getAsJsonArray("region") : null;
		if (region == null || region.size() != 6) {
			throw new IOException("Cannot reconcile tileset without a six-value boundingVolume.region");
		}
		double[] transform = root.has("transform")
				? GlbSemanticValidator.matrix(root.get("transform")) : GlbSemanticValidator.identity();
		var scan = new GlbSemanticValidator().validate(glbFile, directory, transform, null);
		if (!scan.errors().isEmpty()) {
			throw new IOException("Cannot derive final GLB bounds: " + scan.errors().get(0));
		}
		double[] actual = scan.geodeticBounds();
		for (double value : actual) if (!Double.isFinite(value)) {
			throw new IOException("Cannot derive finite final GLB bounds");
		}
		JsonArray expanded = new JsonArray();
		expanded.add(Math.min(region.get(0).getAsDouble(), actual[0]));
		expanded.add(Math.min(region.get(1).getAsDouble(), actual[1]));
		expanded.add(Math.max(region.get(2).getAsDouble(), actual[2]));
		expanded.add(Math.max(region.get(3).getAsDouble(), actual[3]));
		expanded.add(Math.min(region.get(4).getAsDouble(), actual[4]));
		expanded.add(Math.max(region.get(5).getAsDouble(), actual[5]));
		volume.add("region", expanded);

		Path temporary = Files.createTempFile(directory, tilesetFile.getFileName().toString(), ".bounds.tmp");
		try {
			try (Writer writer = Files.newBufferedWriter(temporary, UTF_8)) {
				GSON.toJson(tileset, writer);
			}
			try {
				Files.move(temporary, tilesetFile, ATOMIC_MOVE, REPLACE_EXISTING);
			} catch (IOException atomicMoveFailure) {
				Files.move(temporary, tilesetFile, REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static JsonObject requireObject(JsonObject parent, String property) throws IOException {
		if (!parent.has(property) || !parent.get(property).isJsonObject()) {
			throw new IOException("Tileset is missing object " + property);
		}
		return parent.getAsJsonObject(property);
	}
}
