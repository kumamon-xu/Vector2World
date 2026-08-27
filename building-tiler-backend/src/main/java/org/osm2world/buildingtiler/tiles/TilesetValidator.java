package org.osm2world.buildingtiler.tiles;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
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

	public ValidationResult validate(Path baseDirectory) throws IOException {
		Path root = baseDirectory.toAbsolutePath().normalize();
		ValidationState state = new ValidationState(root);
		validateTileset(root.resolve("tileset.json"), state, true);
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
		validateFiniteArray(entry.get("transform"), 16, "transform", state);
		JsonObject volume = object(entry.get("boundingVolume"));
		if (!volume.isEmpty()) validateFiniteArray(volume.get("region"), 6, "boundingVolume.region", state);

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
		byte[] bytes = Files.readAllBytes(file);
		if (bytes.length < 20) {
			state.errors.add("GLB is too short: " + file);
			return;
		}
		ByteBuffer buffer = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN);
		int magic = buffer.getInt();
		int version = buffer.getInt();
		long declaredLength = Integer.toUnsignedLong(buffer.getInt());
		if (magic != GLB_MAGIC) state.errors.add("Invalid GLB magic: " + file);
		if (version != 2) state.errors.add("Expected GLB version 2: " + file);
		if (declaredLength != bytes.length) state.errors.add("GLB length mismatch: " + file);

		int jsonLength = buffer.getInt();
		int chunkType = buffer.getInt();
		if (chunkType != GLB_JSON_CHUNK || jsonLength < 2 || jsonLength > buffer.remaining()) {
			state.errors.add("Invalid GLB JSON chunk: " + file);
			return;
		}
		byte[] jsonBytes = new byte[jsonLength];
		buffer.get(jsonBytes);
		try {
			JsonObject gltf = JsonParser.parseString(new String(jsonBytes, UTF_8).trim()).getAsJsonObject();
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

	private static Path resolveUri(Path directory, String rawUri, ValidationState state) {
		try {
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
