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
		validateTileset(root.resolve("tileset.json"), state, true, GlbSemanticValidator.identity(), true);
		if (expectedGlbCount >= 0 && state.visitedGlbs.size() != expectedGlbCount) {
			state.errors.add("Expected " + expectedGlbCount + " GLB contents but tileset references "
					+ state.visitedGlbs.size());
		}
		reconcileReports(state, expectedGlbCount);
		return new ValidationResult(state.errors.isEmpty(), state.assetVersion,
				state.visitedTilesets.size(), state.visitedGlbs.size(),
				state.vertexCount, state.triangleCount, state.slopedSurfaceTriangleCount,
				state.minimumModelHeight == Double.POSITIVE_INFINITY ? Double.NaN : state.minimumModelHeight,
				state.maximumModelHeight == Double.NEGATIVE_INFINITY ? Double.NaN : state.maximumModelHeight,
				GlbSemanticValidator.PROFILE,
				List.copyOf(state.errors), Set.copyOf(state.extensionsUsed));
	}

	/**
	 * Reconciles result metadata written after a successful semantic validation. The staging directory is
	 * private to the job, so GLB geometry cannot change between these two phases; only the newly written
	 * manifest, report and ledger need to be checked. GLB JSON metadata is still reread to prove the ledger
	 * mapping, while the potentially large BIN chunks are not decoded a second time.
	 */
	public ValidationResult validateReconciliation(Path baseDirectory, int expectedGlbCount,
			ValidationResult semanticValidation) throws IOException {
		if (semanticValidation == null || !semanticValidation.valid()) {
			throw new IllegalArgumentException("A successful semantic validation is required before reconciliation");
		}
		Path root = baseDirectory.toAbsolutePath().normalize();
		ValidationState state = new ValidationState(root);
		validateTileset(root.resolve("tileset.json"), state, true, GlbSemanticValidator.identity(), false);
		if (expectedGlbCount >= 0 && state.visitedGlbs.size() != expectedGlbCount) {
			state.errors.add("Expected " + expectedGlbCount + " GLB contents but tileset references "
					+ state.visitedGlbs.size());
		}
		reconcileReports(state, expectedGlbCount);
		return new ValidationResult(state.errors.isEmpty(), state.assetVersion,
				state.visitedTilesets.size(), state.visitedGlbs.size(),
				semanticValidation.vertexCount(), semanticValidation.triangleCount(),
				semanticValidation.slopedSurfaceTriangleCount(), semanticValidation.minimumModelHeight(),
				semanticValidation.maximumModelHeight(), semanticValidation.validationProfile(),
				List.copyOf(state.errors), semanticValidation.extensionsUsed());
	}

	private static void validateTileset(Path file, ValidationState state, boolean rootTileset,
			double[] inheritedTransform, boolean validateGeometry) throws IOException {
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
		validateEntry(root, file.getParent(), state, inheritedTransform, validateGeometry);
	}

	private static void validateEntry(JsonObject entry, Path directory, ValidationState state,
			double[] parentTransform, boolean validateGeometry) throws IOException {
		JsonElement geometricError = entry.get("geometricError");
		if (geometricError == null || !finiteNonNegative(geometricError)) {
			state.errors.add("Tile geometricError must be finite and non-negative");
		}
		validateFiniteArray(entry.get("transform"), 16, "transform", state);
		double[] transform = entry.has("transform")
				? GlbSemanticValidator.matrix(entry.get("transform")) : GlbSemanticValidator.identity();
		double[] cumulativeTransform = GlbSemanticValidator.multiply(parentTransform, transform);
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
				if (lower.endsWith(".json")) validateTileset(target, state, false, cumulativeTransform,
						validateGeometry);
				else if (lower.endsWith(".glb")) {
					if (validateGeometry) validateGlb(target, state, cumulativeTransform, volume);
					else collectGlbMetadata(target, state);
				}
				else state.errors.add("Unsupported tile content URI: " + uri);
			}
		}

		JsonElement children = entry.get("children");
		if (children != null && children.isJsonArray()) {
			for (JsonElement child : children.getAsJsonArray()) {
				if (child.isJsonObject()) validateEntry(child.getAsJsonObject(), directory, state,
						cumulativeTransform, validateGeometry);
			}
		}
	}

	private static void collectGlbMetadata(Path file, ValidationState state) {
		file = safePath(file, state);
		if (file == null || !state.visitedGlbs.add(file)) return;
		if (!Files.isRegularFile(file)) {
			state.errors.add("Missing GLB: " + file);
			return;
		}
		try {
			for (String partId : new FinalGlbFeatureIndex().readPartIds(file)) {
				if (!state.finalPartIds.add(partId)) {
					state.errors.add("Duplicate final GLB feature part metadata: " + partId);
				}
			}
		} catch (IOException exception) {
			state.errors.add(exception.getMessage());
		}
	}

	private static void validateGlb(Path file, ValidationState state, double[] transform,
			JsonObject boundingVolume) throws IOException {
		file = safePath(file, state);
		if (file == null) return;
		if (!state.visitedGlbs.add(file)) return;
		if (!Files.isRegularFile(file)) {
			state.errors.add("Missing GLB: " + file);
			return;
		}
		var validation = new GlbSemanticValidator().validate(file, state.baseDirectory, transform, boundingVolume);
		state.vertexCount += validation.vertexCount();
		state.triangleCount += validation.triangleCount();
		state.slopedSurfaceTriangleCount += validation.slopedSurfaceTriangleCount();
		if (Double.isFinite(validation.minimumModelHeight())) {
			state.minimumModelHeight = Math.min(state.minimumModelHeight, validation.minimumModelHeight());
			state.maximumModelHeight = Math.max(state.maximumModelHeight, validation.maximumModelHeight());
		}
		state.errors.addAll(validation.errors());
		try {
			for (String partId : new FinalGlbFeatureIndex().readPartIds(file)) {
				if (!state.finalPartIds.add(partId)) {
					state.errors.add("Duplicate final GLB feature part metadata: " + partId);
				}
			}
		} catch (IOException exception) {
			state.errors.add(exception.getMessage());
		}
		try {
			byte[] bytes = Files.readAllBytes(file);
			if (bytes.length >= 20) {
				ByteBuffer buffer = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN);
				buffer.position(12);
				int jsonLength = buffer.getInt();
				buffer.getInt();
				if (jsonLength > 0 && jsonLength <= buffer.remaining()) {
					byte[] json = new byte[jsonLength];
					buffer.get(json);
					JsonElement extensions = JsonParser.parseString(new String(json, UTF_8).trim())
							.getAsJsonObject().get("extensionsUsed");
					if (extensions != null && extensions.isJsonArray()) for (JsonElement extension : extensions.getAsJsonArray()) {
						state.extensionsUsed.add(extension.getAsString());
					}
				}
			}
		} catch (RuntimeException ignored) {
			// Semantic validation already emitted the precise parse error.
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

	private static void reconcileReports(ValidationState state, int expectedGlbCount) {
		Path report = state.baseDirectory.resolve("generation-report.json");
		Path manifest = state.baseDirectory.resolve("manifest.json");
		Path ledger = state.baseDirectory.resolve("modeling-ledger.json");
		if (!Files.exists(report) && !Files.exists(manifest)) return;
		try {
			if (!Files.isRegularFile(report) || !Files.isRegularFile(manifest) || !Files.isRegularFile(ledger)) {
				state.errors.add("Batch result must contain manifest.json, generation-report.json and modeling-ledger.json");
				return;
			}
			JsonObject reportJson;
			JsonObject manifestJson;
			JsonObject ledgerJson;
			try (var reader = Files.newBufferedReader(report, UTF_8)) {
				reportJson = JsonParser.parseReader(reader).getAsJsonObject();
			}
			try (var reader = Files.newBufferedReader(manifest, UTF_8)) {
				manifestJson = JsonParser.parseReader(reader).getAsJsonObject();
			}
			try (var reader = Files.newBufferedReader(ledger, UTF_8)) {
				ledgerJson = JsonParser.parseReader(reader).getAsJsonObject();
			}
			int reportedContents = reportJson.get("successfulTileContents").getAsInt();
			int manifestContents = manifestJson.getAsJsonArray("tileContents").size();
			if (reportedContents != state.visitedGlbs.size() || manifestContents != state.visitedGlbs.size()) {
				state.errors.add("Manifest/report tile content counts do not match the tileset tree");
			}
			if (expectedGlbCount >= 0 && reportedContents != expectedGlbCount) {
				state.errors.add("Generation report content count does not match the expected result");
			}
			Set<String> modeledPartIds = new LinkedHashSet<>();
			JsonArray entries = ledgerJson.getAsJsonArray("entries");
			if (entries == null) {
				state.errors.add("Modeling ledger is missing entries");
				return;
			}
			for (JsonElement element : entries) {
				JsonObject entry = object(element);
				String status = string(entry.get("status"));
				String partId = string(entry.get("partId"));
				if (status == null || partId == null || "PENDING".equals(status)) {
					state.errors.add("Modeling ledger contains an invalid or non-terminal entry");
				} else if ("MODELED".equals(status) && !modeledPartIds.add(partId)) {
					state.errors.add("Modeling ledger contains duplicate modeled part: " + partId);
				}
			}
			JsonObject manifestLedger = object(manifestJson.get("modelingLedger"));
			JsonObject reportLedger = object(reportJson.get("modelingLedger"));
			JsonObject ledgerSummary = object(ledgerJson.get("summary"));
			int inputParts = entries.size();
			if (integer(manifestLedger.get("inputParts")) != inputParts
					|| integer(reportLedger.get("inputParts")) != inputParts
					|| integer(ledgerSummary.get("inputParts")) != inputParts) {
				state.errors.add("Modeling ledger input part counts do not reconcile");
			}
			if (integer(reportJson.get("modeledParts")) != modeledPartIds.size()
					|| integer(manifestLedger.get("modeledParts")) != modeledPartIds.size()) {
				state.errors.add("Modeled part counts do not reconcile with the modeling ledger");
			}
			if (!modeledPartIds.equals(state.finalPartIds)) {
				state.errors.add("Final GLB feature-part metadata does not match MODELED ledger entries");
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

	private static int integer(JsonElement element) {
		try { return element == null ? -1 : element.getAsInt(); }
		catch (RuntimeException exception) { return -1; }
	}

	public record ValidationResult(
			boolean valid,
			String assetVersion,
			int tilesetCount,
			int glbCount,
			long vertexCount,
			long triangleCount,
			long slopedSurfaceTriangleCount,
			double minimumModelHeight,
			double maximumModelHeight,
			String validationProfile,
			List<String> errors,
			Set<String> extensionsUsed) {
	}

	private static final class ValidationState {
		final Path baseDirectory;
		final Set<Path> visitedTilesets = new LinkedHashSet<>();
		final Set<Path> visitedGlbs = new LinkedHashSet<>();
		final List<String> errors = new ArrayList<>();
		final Set<String> extensionsUsed = new LinkedHashSet<>();
		final Set<String> finalPartIds = new LinkedHashSet<>();
		long vertexCount;
		long triangleCount;
		long slopedSurfaceTriangleCount;
		double minimumModelHeight = Double.POSITIVE_INFINITY;
		double maximumModelHeight = Double.NEGATIVE_INFINITY;
		String assetVersion;

		ValidationState(Path baseDirectory) {
			this.baseDirectory = baseDirectory;
		}
	}

}
