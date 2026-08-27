package org.osm2world.buildingtiler.tiles;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class TilesetValidator {

	private static final int GLB_MAGIC = 0x46546c67;
	private static final int GLB_JSON_CHUNK = 0x4e4f534a;
	private static final int MAX_GLTF_JSON_BYTES = 64 * 1024 * 1024;

	public ValidationResult validate(Path baseDirectory) throws IOException {
		return validate(baseDirectory, -1);
	}

	public ValidationResult validate(Path baseDirectory, int expectedGlbCount) throws IOException {
		Path root = baseDirectory.toAbsolutePath().normalize();
		ValidationState state = new ValidationState(root);
		validateTileset(root.resolve("tileset.json"), state, true);
		if (expectedGlbCount >= 0 && state.visitedGlbs.size() != expectedGlbCount) {
			state.errors.add("Expected " + expectedGlbCount + " GLB contents but tileset references "
					+ state.visitedGlbs.size());
		}
		reconcileReports(state, expectedGlbCount);
		return new ValidationResult(state.errors.isEmpty(), state.assetVersion,
				state.visitedTilesets.size(), state.visitedGlbs.size(),
				List.copyOf(state.errors), Set.copyOf(state.extensionsUsed));
	}

	private static void validateTileset(Path file, ValidationState state, boolean rootTileset) throws IOException {
		file = safePath(file, state);
		if (file == null) return;
		if (!state.visitedTilesets.add(file)) return;
		if (!Files.isRegularFile(file)) {
			state.errors.add("Missing tileset: " + file);
			return;
		}

		JsonObject json;
		try (var reader = Files.newBufferedReader(file, UTF_8)) {
			json = JsonParser.parseReader(reader).getAsJsonObject();
		} catch (RuntimeException exception) {
			state.errors.add("Invalid tileset JSON " + file + ": " + exception.getMessage());
			return;
		}

		JsonObject asset = object(json.get("asset"));
		String version = string(asset.get("version"));
		if (rootTileset) state.assetVersion = version;
		if (!"1.1".equals(version)) state.errors.add("Expected 3D Tiles asset.version 1.1 in " + file);
		JsonObject root = object(json.get("root"));
		if (root.isEmpty()) {
			state.errors.add("Missing root tile in " + file);
			return;
		}
		validateEntry(root, file.getParent(), state);
	}

	private static void validateEntry(JsonObject entry, Path directory, ValidationState state) throws IOException {
		JsonElement geometricError = entry.get("geometricError");
		if (geometricError == null || !finiteNonNegative(geometricError)) {
			state.errors.add("Tile geometricError must be finite and non-negative");
		}
		validateFiniteArray(entry.get("transform"), 16, "transform", state);
		JsonObject volume = object(entry.get("boundingVolume"));
		if (volume.isEmpty()) {
			state.errors.add("Tile is missing boundingVolume");
		} else if (volume.has("region")) {
			validateRegion(volume.get("region"), state);
		} else if (volume.has("box")) {
			validateFiniteArray(volume.get("box"), 12, "boundingVolume.box", state);
		} else if (volume.has("sphere")) {
			validateFiniteArray(volume.get("sphere"), 4, "boundingVolume.sphere", state);
		} else {
			state.errors.add("Unsupported boundingVolume");
		}

		JsonObject content = object(entry.get("content"));
		String uri = string(content.get("uri"));
		if (uri == null) uri = string(content.get("url"));
		if (uri != null) {
			Path target = resolveUri(directory, uri, state);
			if (target != null) {
				String lower = target.getFileName().toString().toLowerCase();
				if (lower.endsWith(".json")) validateTileset(target, state, false);
				else if (lower.endsWith(".glb")) validateGlb(target, state);
				else state.errors.add("Unsupported tile content URI: " + uri);
			}
		}

		JsonElement children = entry.get("children");
		if (children != null && children.isJsonArray()) {
			for (JsonElement child : children.getAsJsonArray()) {
				if (child.isJsonObject()) validateEntry(child.getAsJsonObject(), directory, state);
			}
		}
	}

	private static void validateGlb(Path file, ValidationState state) throws IOException {
		file = safePath(file, state);
		if (file == null) return;
		if (!state.visitedGlbs.add(file)) return;
		if (!Files.isRegularFile(file)) {
			state.errors.add("Missing GLB: " + file);
			return;
		}
		long fileSize = Files.size(file);
		if (fileSize < 20) {
			state.errors.add("GLB is too short: " + file);
			return;
		}
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			ByteBuffer header = ByteBuffer.allocate(20).order(LITTLE_ENDIAN);
			readFully(channel, header);
			header.flip();
			int magic = header.getInt();
			int version = header.getInt();
			long declaredLength = Integer.toUnsignedLong(header.getInt());
			if (magic != GLB_MAGIC) state.errors.add("Invalid GLB magic: " + file);
			if (version != 2) state.errors.add("Expected GLB version 2: " + file);
			if (declaredLength != fileSize) state.errors.add("GLB length mismatch: " + file);

			long jsonLengthUnsigned = Integer.toUnsignedLong(header.getInt());
			int chunkType = header.getInt();
			if (chunkType != GLB_JSON_CHUNK || jsonLengthUnsigned < 2
					|| jsonLengthUnsigned > MAX_GLTF_JSON_BYTES || jsonLengthUnsigned > fileSize - 20) {
				state.errors.add("Invalid GLB JSON chunk: " + file);
				return;
			}
			ByteBuffer jsonBuffer = ByteBuffer.allocate((int)jsonLengthUnsigned);
			readFully(channel, jsonBuffer);
			JsonObject gltf = JsonParser.parseString(new String(jsonBuffer.array(), UTF_8).trim()).getAsJsonObject();
			JsonObject asset = object(gltf.get("asset"));
			if (!"2.0".equals(string(asset.get("version")))) {
				state.errors.add("Expected embedded glTF asset.version 2.0: " + file);
			}
			JsonElement extensions = gltf.get("extensionsUsed");
			if (extensions != null && extensions.isJsonArray()) {
				for (JsonElement extension : extensions.getAsJsonArray()) {
					state.extensionsUsed.add(extension.getAsString());
				}
			}
		} catch (RuntimeException exception) {
			state.errors.add("Invalid embedded glTF JSON " + file + ": " + exception.getMessage());
		}
	}

	private static void validateFiniteArray(JsonElement value, int expectedSize, String label,
			ValidationState state) {
		if (value == null) return;
		if (!value.isJsonArray() || value.getAsJsonArray().size() != expectedSize) {
			state.errors.add("Invalid " + label + " array");
			return;
		}
		for (JsonElement element : value.getAsJsonArray()) {
			try {
				if (!Double.isFinite(element.getAsDouble())) state.errors.add("Non-finite " + label + " value");
			} catch (RuntimeException exception) {
				state.errors.add("Non-numeric " + label + " value");
			}
		}
	}

	private static void validateRegion(JsonElement value, ValidationState state) {
		int errorsBefore = state.errors.size();
		validateFiniteArray(value, 6, "boundingVolume.region", state);
		if (state.errors.size() != errorsBefore || value == null || !value.isJsonArray()) return;
		JsonArray region = value.getAsJsonArray();
		double west = region.get(0).getAsDouble();
		double south = region.get(1).getAsDouble();
		double east = region.get(2).getAsDouble();
		double north = region.get(3).getAsDouble();
		double minimumHeight = region.get(4).getAsDouble();
		double maximumHeight = region.get(5).getAsDouble();
		if (west < -Math.PI || east > Math.PI || south < -Math.PI / 2 || north > Math.PI / 2
				|| west > east || south > north || minimumHeight > maximumHeight) {
			state.errors.add("Invalid boundingVolume.region ranges");
		}
	}

	private static Path resolveUri(Path directory, String rawUri, ValidationState state) {
		try {
			if (rawUri.isBlank() || rawUri.contains("\\")) {
				state.errors.add("Tile URI must be non-empty and use '/' separators: " + rawUri);
				return null;
			}
			URI uri = URI.create(rawUri);
			if (uri.isAbsolute() || uri.getAuthority() != null || uri.getQuery() != null || uri.getFragment() != null) {
				state.errors.add("Tile URI must be a local relative path: " + rawUri);
				return null;
			}
			return safePath(directory.resolve(uri.getPath().replace('/', java.io.File.separatorChar)), state);
		} catch (RuntimeException exception) {
			state.errors.add("Invalid tile URI " + rawUri + ": " + exception.getMessage());
			return null;
		}
	}

	private static boolean finiteNonNegative(JsonElement value) {
		try {
			double number = value.getAsDouble();
			return Double.isFinite(number) && number >= 0;
		} catch (RuntimeException exception) {
			return false;
		}
	}

	private static void readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
		while (buffer.hasRemaining()) {
			if (channel.read(buffer) < 0) throw new IOException("Unexpected end of GLB file");
		}
	}

	private static void reconcileReports(ValidationState state, int expectedGlbCount) {
		Path report = state.baseDirectory.resolve("generation-report.json");
		Path manifest = state.baseDirectory.resolve("manifest.json");
		if (!Files.exists(report) && !Files.exists(manifest)) return;
		try {
			if (!Files.isRegularFile(report) || !Files.isRegularFile(manifest)) {
				state.errors.add("Batch result must contain both manifest.json and generation-report.json");
				return;
			}
			JsonObject reportJson;
			JsonObject manifestJson;
			try (var reader = Files.newBufferedReader(report, UTF_8)) {
				reportJson = JsonParser.parseReader(reader).getAsJsonObject();
			}
			try (var reader = Files.newBufferedReader(manifest, UTF_8)) {
				manifestJson = JsonParser.parseReader(reader).getAsJsonObject();
			}
			int reportedContents = reportJson.get("successfulTileContents").getAsInt();
			int manifestContents = manifestJson.getAsJsonArray("tileContents").size();
			if (reportedContents != state.visitedGlbs.size() || manifestContents != state.visitedGlbs.size()) {
				state.errors.add("Manifest/report tile content counts do not match the tileset tree");
			}
			if (expectedGlbCount >= 0 && reportedContents != expectedGlbCount) {
				state.errors.add("Generation report content count does not match the expected result");
			}
		} catch (Exception exception) {
			state.errors.add("Invalid manifest/report reconciliation data: " + exception.getMessage());
		}
	}

	private static Path safePath(Path path, ValidationState state) {
		Path normalized = path.toAbsolutePath().normalize();
		if (!normalized.startsWith(state.baseDirectory)) {
			state.errors.add("Tile content escapes result directory: " + path);
			return null;
		}
		return normalized;
	}

	private static JsonObject object(JsonElement element) {
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
	}

	private static String string(JsonElement element) {
		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}

	public record ValidationResult(
			boolean valid,
			String assetVersion,
			int tilesetCount,
			int glbCount,
			List<String> errors,
			Set<String> extensionsUsed) {
	}

	private static final class ValidationState {
		final Path baseDirectory;
		final Set<Path> visitedTilesets = new LinkedHashSet<>();
		final Set<Path> visitedGlbs = new LinkedHashSet<>();
		final List<String> errors = new ArrayList<>();
		final Set<String> extensionsUsed = new LinkedHashSet<>();
		String assetVersion;

		ValidationState(Path baseDirectory) {
			this.baseDirectory = baseDirectory;
		}
	}

}
