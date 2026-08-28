package org.osm2world.buildingtiler.tiles;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Production-side validation for the binary and semantic portions of a GLB that are required by
 * Vector2World. The official Khronos validator remains the CI conformance gate; this validator is
 * deliberately embedded in the publisher so corrupt accessors or misplaced geometry cannot be
 * published merely because an external validation executable is unavailable.
 */
final class GlbSemanticValidator {

	static final String PROFILE = "vector2world-glb-semantic-v1";
	private static final int GLB_MAGIC = 0x46546c67;
	private static final int JSON_CHUNK = 0x4e4f534a;
	private static final int BIN_CHUNK = 0x004e4942;
	private static final int MAX_JSON_BYTES = 64 * 1024 * 1024;
	private static final Set<String> ALLOWED_EXTENSIONS = Set.of("KHR_mesh_quantization");
	private static final double WGS84_A = 6_378_137.0;
	private static final double WGS84_B = 6_356_752.314245179;
	private static final double WGS84_E2 = 1.0 - WGS84_B * WGS84_B / (WGS84_A * WGS84_A);
	private static final double WGS84_EP2 = (WGS84_A * WGS84_A - WGS84_B * WGS84_B)
			/ (WGS84_B * WGS84_B);
	/** glTF is Y-up; 3D Tiles applies this conversion before the tile's Z-up transform. */
	private static final double[] GLTF_Y_UP_TO_TILE_Z_UP = {
			1, 0, 0, 0,
			0, 0, 1, 0,
			0, -1, 0, 0,
			0, 0, 0, 1};

	Result validate(Path file, Path resultRoot, double[] tilesetTransform, JsonObject boundingVolume) {
		List<String> errors = new ArrayList<>();
		try {
			Document document = read(file, resultRoot, errors);
			if (document == null) return Result.empty(errors);
			Validator validator = new Validator(document, file, resultRoot, tilesetTransform,
					boundingVolume, errors);
			validator.validate();
			return new Result(validator.vertexCount, validator.triangleCount,
					validator.slopedSurfaceTriangleCount, validator.minimumModelHeight,
					validator.maximumModelHeight, validator.minimumLongitude, validator.minimumLatitude,
					validator.maximumLongitude, validator.maximumLatitude,
					validator.minimumEllipsoidHeight, validator.maximumEllipsoidHeight,
					List.copyOf(errors));
		} catch (RuntimeException | IOException exception) {
			errors.add(file + ": GLB semantic validation failed: " + exception.getMessage());
			return Result.empty(errors);
		}
	}

	private static Document read(Path file, Path resultRoot, List<String> errors) throws IOException {
		byte[] bytes = Files.readAllBytes(file);
		if (bytes.length < 20) {
			errors.add(file + ": GLB is too short");
			return null;
		}
		ByteBuffer input = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN);
		if (input.getInt() != GLB_MAGIC) errors.add(file + ": Invalid GLB magic");
		if (input.getInt() != 2) errors.add(file + ": Expected GLB version 2");
		long declaredLength = Integer.toUnsignedLong(input.getInt());
		if (declaredLength != bytes.length) errors.add(file + ": GLB length mismatch");

		long jsonLength = Integer.toUnsignedLong(input.getInt());
		int jsonType = input.getInt();
		if (jsonType != JSON_CHUNK || jsonLength < 2 || jsonLength > MAX_JSON_BYTES
				|| jsonLength > input.remaining()) {
			errors.add(file + ": invalid GLB JSON chunk");
			return null;
		}
		byte[] jsonBytes = new byte[(int)jsonLength];
		input.get(jsonBytes);
		JsonObject json;
		try {
			json = JsonParser.parseString(new String(jsonBytes, UTF_8).trim()).getAsJsonObject();
		} catch (RuntimeException exception) {
			errors.add(file + ": invalid embedded glTF JSON: " + exception.getMessage());
			return null;
		}

		byte[] bin = new byte[0];
		if (input.hasRemaining()) {
			if (input.remaining() < 8) {
				errors.add(file + ": truncated GLB BIN chunk header");
				return new Document(json, bin);
			}
			long binLength = Integer.toUnsignedLong(input.getInt());
			int binType = input.getInt();
			if (binType != BIN_CHUNK || binLength > input.remaining() || binLength > Integer.MAX_VALUE) {
				errors.add(file + ": invalid or truncated GLB BIN chunk");
				return new Document(json, bin);
			}
			bin = new byte[(int)binLength];
			input.get(bin);
			if (input.hasRemaining()) errors.add(file + ": unexpected bytes after GLB BIN chunk");
		}
		return new Document(json, bin);
	}

	private static final class Validator {
		private final Document document;
		private final JsonObject gltf;
		private final Path file;
		private final Path resultRoot;
		private final double[] tilesetTransform;
		private final JsonObject boundingVolume;
		private final List<String> errors;
		private final List<byte[]> buffers = new ArrayList<>();
		private long vertexCount;
		private long triangleCount;
		private long slopedSurfaceTriangleCount;
		private double minimumModelHeight = Double.POSITIVE_INFINITY;
		private double maximumModelHeight = Double.NEGATIVE_INFINITY;
		private double minimumLongitude = Double.POSITIVE_INFINITY;
		private double minimumLatitude = Double.POSITIVE_INFINITY;
		private double maximumLongitude = Double.NEGATIVE_INFINITY;
		private double maximumLatitude = Double.NEGATIVE_INFINITY;
		private double minimumEllipsoidHeight = Double.POSITIVE_INFINITY;
		private double maximumEllipsoidHeight = Double.NEGATIVE_INFINITY;

		Validator(Document document, Path file, Path resultRoot, double[] tilesetTransform,
				JsonObject boundingVolume, List<String> errors) {
			this.document = document;
			this.gltf = document.json();
			this.file = file;
			this.resultRoot = resultRoot;
			this.tilesetTransform = tilesetTransform;
			this.boundingVolume = boundingVolume;
			this.errors = errors;
		}

		void validate() throws IOException {
			JsonObject asset = object(gltf.get("asset"));
			if (!"2.0".equals(string(asset.get("version")))) error("asset.version must be 2.0");
			validateExtensions();
			loadBuffers();
			validateBufferViews();
			validateAccessors();
			validateImagesAndTextures();
			validateMeshes();
			validateNodeGraphAndPositions();
			if (vertexCount == 0) error("no referenced POSITION vertices");
			if (triangleCount == 0) error("no referenced triangles");
		}

		private void validateExtensions() {
			for (String property : List.of("extensionsUsed", "extensionsRequired")) {
				for (JsonElement value : array(gltf.get(property))) {
					String extension = string(value);
					if (extension == null || !ALLOWED_EXTENSIONS.contains(extension)) {
						error("unsupported " + property + " entry: " + extension);
					}
				}
			}
		}

		private void loadBuffers() throws IOException {
			JsonArray definitions = array(gltf.get("buffers"));
			if (definitions.isEmpty()) {
				error("buffers array is missing or empty");
				return;
			}
			for (int i = 0; i < definitions.size(); i++) {
				JsonObject definition = object(definitions.get(i));
				int declared = integer(definition.get("byteLength"), -1);
				String uri = string(definition.get("uri"));
				byte[] data;
				if (uri == null) {
					if (i != 0) {
						error("only GLB buffer 0 may omit uri");
						data = new byte[0];
					} else data = document.bin();
				} else data = loadUri(uri, "buffer " + i);
				if (declared < 0) error("buffer " + i + " has invalid byteLength");
				else if (declared > data.length) error("buffer " + i + " byteLength exceeds available data");
				buffers.add(data);
			}
		}

		private byte[] loadUri(String raw, String label) throws IOException {
			if (raw.startsWith("data:")) {
				int comma = raw.indexOf(',');
				if (comma < 0 || !raw.substring(0, comma).endsWith(";base64")) {
					error(label + " uses an invalid data URI");
					return new byte[0];
				}
				try { return Base64.getDecoder().decode(raw.substring(comma + 1)); }
				catch (IllegalArgumentException exception) {
					error(label + " contains invalid base64 data");
					return new byte[0];
				}
			}
			try {
				URI uri = URI.create(raw);
				if (uri.isAbsolute() || uri.getAuthority() != null || uri.getQuery() != null
						|| uri.getFragment() != null || raw.contains("\\")) {
					error(label + " URI must be a local relative path: " + raw);
					return new byte[0];
				}
				Path target = file.getParent().resolve(uri.getPath().replace('/', java.io.File.separatorChar))
						.toAbsolutePath().normalize();
				if (!target.startsWith(resultRoot) || !Files.isRegularFile(target)) {
					error(label + " resource is missing or escapes result directory: " + raw);
					return new byte[0];
				}
				return Files.readAllBytes(target);
			} catch (RuntimeException exception) {
				error(label + " has invalid URI: " + raw);
				return new byte[0];
			}
		}

		private void validateBufferViews() {
			JsonArray views = array(gltf.get("bufferViews"));
			for (int i = 0; i < views.size(); i++) {
				JsonObject view = object(views.get(i));
				int buffer = integer(view.get("buffer"), -1);
				int offset = integer(view.get("byteOffset"), 0);
				int length = integer(view.get("byteLength"), -1);
				int stride = integer(view.get("byteStride"), 0);
				if (buffer < 0 || buffer >= buffers.size()) error("bufferView " + i + " references invalid buffer");
				else if (offset < 0 || length <= 0 || (long)offset + length > buffers.get(buffer).length) {
					error("bufferView " + i + " exceeds buffer bounds");
				}
				if (stride != 0 && (stride < 4 || stride > 252 || stride % 4 != 0)) {
					error("bufferView " + i + " has invalid byteStride");
				}
			}
		}

		private void validateAccessors() {
			JsonArray accessors = array(gltf.get("accessors"));
			for (int i = 0; i < accessors.size(); i++) {
				JsonObject accessor = object(accessors.get(i));
				int count = integer(accessor.get("count"), -1);
				int componentType = integer(accessor.get("componentType"), -1);
				int components = componentCount(string(accessor.get("type")));
				int size = componentSize(componentType);
				if (count <= 0) error("accessor " + i + " has invalid count");
				if (components == 0) error("accessor " + i + " has invalid type");
				if (size == 0) error("accessor " + i + " has invalid componentType");
				if (accessor.has("bufferView")) validateAccessorRange(i, accessor, count, components, size);
				else if (!accessor.has("sparse")) error("accessor " + i + " has neither bufferView nor sparse data");
				if (accessor.has("sparse")) validateSparseRange(i, accessor, count, components, size);
			}
		}

		private void validateAccessorRange(int index, JsonObject accessor, int count, int components, int size) {
			int viewIndex = integer(accessor.get("bufferView"), -1);
			JsonArray views = array(gltf.get("bufferViews"));
			if (viewIndex < 0 || viewIndex >= views.size() || count <= 0 || components == 0 || size == 0) {
				error("accessor " + index + " references invalid bufferView");
				return;
			}
			JsonObject view = object(views.get(viewIndex));
			int offset = integer(accessor.get("byteOffset"), 0);
			int elementSize = components * size;
			int stride = integer(view.get("byteStride"), elementSize);
			long required = offset + (long)(count - 1) * stride + elementSize;
			if (offset < 0 || offset % size != 0 || stride < elementSize
					|| required > integer(view.get("byteLength"), -1)) {
				error("accessor " + index + " exceeds bufferView or violates alignment/stride");
			}
		}

		private void validateSparseRange(int index, JsonObject accessor, int accessorCount,
				int components, int valueComponentSize) {
			JsonObject sparse = object(accessor.get("sparse"));
			int count = integer(sparse.get("count"), -1);
			JsonObject indices = object(sparse.get("indices"));
			JsonObject values = object(sparse.get("values"));
			int indexType = integer(indices.get("componentType"), -1);
			if (count <= 0 || count > accessorCount) error("accessor " + index + " has invalid sparse.count");
			if (indexType != 5121 && indexType != 5123 && indexType != 5125) {
				error("accessor " + index + " has invalid sparse index componentType");
			}
			validateRawRange("accessor " + index + " sparse indices", indices, count, componentSize(indexType));
			validateRawRange("accessor " + index + " sparse values", values, count,
					components * valueComponentSize);
		}

		private void validateRawRange(String label, JsonObject value, int count, int itemSize) {
			JsonArray views = array(gltf.get("bufferViews"));
			int viewIndex = integer(value.get("bufferView"), -1);
			int offset = integer(value.get("byteOffset"), 0);
			if (viewIndex < 0 || viewIndex >= views.size() || count <= 0 || itemSize <= 0) {
				error(label + " references invalid bufferView");
				return;
			}
			int length = integer(object(views.get(viewIndex)).get("byteLength"), -1);
			if (offset < 0 || offset % Math.min(itemSize, 4) != 0 || (long)offset + (long)count * itemSize > length) {
				error(label + " exceeds bufferView bounds");
			}
		}

		private void validateImagesAndTextures() throws IOException {
			JsonArray views = array(gltf.get("bufferViews"));
			JsonArray images = array(gltf.get("images"));
			for (int i = 0; i < images.size(); i++) {
				JsonObject image = object(images.get(i));
				boolean uri = image.has("uri");
				boolean view = image.has("bufferView");
				if (uri == view) {
					error("image " + i + " must contain exactly one of uri or bufferView");
					continue;
				}
				byte[] data;
				if (uri) data = loadUri(string(image.get("uri")), "image " + i);
				else {
					int index = integer(image.get("bufferView"), -1);
					if (index < 0 || index >= views.size() || string(image.get("mimeType")) == null) {
						error("image " + i + " has invalid bufferView or missing mimeType");
						continue;
					}
					data = sliceView(index);
				}
				if (data.length == 0) error("image " + i + " is empty");
			}
			JsonArray textures = array(gltf.get("textures"));
			for (int i = 0; i < textures.size(); i++) {
				int source = integer(object(textures.get(i)).get("source"), -1);
				if (source < 0 || source >= images.size()) error("texture " + i + " references invalid image");
			}
			JsonArray materials = array(gltf.get("materials"));
			for (int i = 0; i < materials.size(); i++) {
				JsonObject pbr = object(object(materials.get(i)).get("pbrMetallicRoughness"));
				validateTextureInfo(pbr.get("baseColorTexture"), textures.size(), "material " + i + " baseColorTexture");
				validateTextureInfo(pbr.get("metallicRoughnessTexture"), textures.size(),
						"material " + i + " metallicRoughnessTexture");
				validateTextureInfo(object(materials.get(i)).get("normalTexture"), textures.size(),
						"material " + i + " normalTexture");
			}
		}

		private void validateTextureInfo(JsonElement element, int textureCount, String label) {
			if (element == null) return;
			int index = integer(object(element).get("index"), -1);
			if (index < 0 || index >= textureCount) error(label + " references invalid texture");
		}

		private void validateMeshes() {
			JsonArray meshes = array(gltf.get("meshes"));
			JsonArray accessors = array(gltf.get("accessors"));
			JsonArray materials = array(gltf.get("materials"));
			if (meshes.isEmpty()) error("meshes array is missing or empty");
			for (int meshIndex = 0; meshIndex < meshes.size(); meshIndex++) {
				JsonArray primitives = array(object(meshes.get(meshIndex)).get("primitives"));
				if (primitives.isEmpty()) error("mesh " + meshIndex + " has no primitives");
				for (int primitiveIndex = 0; primitiveIndex < primitives.size(); primitiveIndex++) {
					String label = "mesh " + meshIndex + " primitive " + primitiveIndex;
					JsonObject primitive = object(primitives.get(primitiveIndex));
					int mode = integer(primitive.get("mode"), 4);
					if (mode != 4) {
						error(label + " is not TRIANGLES");
						continue;
					}
					JsonObject attributes = object(primitive.get("attributes"));
					int position = integer(attributes.get("POSITION"), -1);
					if (position < 0 || position >= accessors.size()) {
						error(label + " has no valid POSITION accessor");
						continue;
					}
					JsonObject positionAccessor = object(accessors.get(position));
					if (!"VEC3".equals(string(positionAccessor.get("type")))) error(label + " POSITION is not VEC3");
					int positions = integer(positionAccessor.get("count"), 0);
					for (var attribute : attributes.entrySet()) {
						int accessorIndex = integer(attribute.getValue(), -1);
						if (accessorIndex < 0 || accessorIndex >= accessors.size()) {
							error(label + " has invalid " + attribute.getKey() + " accessor");
						} else if (integer(object(accessors.get(accessorIndex)).get("count"), -1) != positions) {
							error(label + " attribute counts differ");
						}
					}
					int elementCount = positions;
					if (primitive.has("indices")) {
						int indices = integer(primitive.get("indices"), -1);
						if (indices < 0 || indices >= accessors.size()) error(label + " references invalid indices accessor");
						else {
							JsonObject indexAccessor = object(accessors.get(indices));
							if (!"SCALAR".equals(string(indexAccessor.get("type")))) error(label + " indices are not SCALAR");
							int type = integer(indexAccessor.get("componentType"), -1);
							if (type != 5121 && type != 5123 && type != 5125) error(label + " indices use invalid componentType");
							long[] values = readIndices(indices);
							elementCount = values.length;
							for (long value : values) if (value < 0 || value >= positions) {
								error(label + " contains out-of-range index " + value);
								break;
							}
						}
					}
					if (elementCount == 0 || elementCount % 3 != 0) error(label + " has empty/non-triangular element count");
					if (primitive.has("material")) {
						int material = integer(primitive.get("material"), -1);
						if (material < 0 || material >= materials.size()) error(label + " references invalid material");
					}
				}
			}
		}

		private void validateNodeGraphAndPositions() {
			JsonArray nodes = array(gltf.get("nodes"));
			JsonArray scenes = array(gltf.get("scenes"));
			JsonArray meshes = array(gltf.get("meshes"));
			if (nodes.isEmpty() || scenes.isEmpty()) {
				error("nodes/scenes are missing or empty");
				return;
			}
			int sceneIndex = integer(gltf.get("scene"), 0);
			if (sceneIndex < 0 || sceneIndex >= scenes.size()) {
				error("default scene index is invalid");
				return;
			}
			Set<Integer> path = new HashSet<>();
			for (JsonElement root : array(object(scenes.get(sceneIndex)).get("nodes"))) {
				int node = integer(root, -1);
				if (node < 0 || node >= nodes.size()) error("scene references invalid root node");
				else visitNode(node, identity(), path, nodes, meshes);
			}
		}

		private void visitNode(int index, double[] parent, Set<Integer> path, JsonArray nodes, JsonArray meshes) {
			if (!path.add(index)) {
				error("node graph contains a cycle at node " + index);
				return;
			}
			JsonObject node = object(nodes.get(index));
			double[] transform = multiply(parent, nodeTransform(node));
			if (node.has("mesh")) {
				int meshIndex = integer(node.get("mesh"), -1);
				if (meshIndex < 0 || meshIndex >= meshes.size()) error("node " + index + " references invalid mesh");
				else inspectMeshPositions(meshIndex, transform,
						multiply(tilesetTransform, multiply(GLTF_Y_UP_TO_TILE_Z_UP, transform)), meshes);
			}
			for (JsonElement childValue : array(node.get("children"))) {
				int child = integer(childValue, -1);
				if (child < 0 || child >= nodes.size()) error("node " + index + " references invalid child");
				else visitNode(child, transform, path, nodes, meshes);
			}
			path.remove(index);
		}

		private void inspectMeshPositions(int meshIndex, double[] model, double[] world, JsonArray meshes) {
			JsonArray accessors = array(gltf.get("accessors"));
			for (JsonElement primitiveElement : array(object(meshes.get(meshIndex)).get("primitives"))) {
				JsonObject primitive = object(primitiveElement);
				if (integer(primitive.get("mode"), 4) != 4) continue;
				int accessor = integer(object(primitive.get("attributes")).get("POSITION"), -1);
				if (accessor < 0 || accessor >= accessors.size()) continue;
				double[][] positions = readVectors(accessor, 3);
				double[][] modelPositions = new double[positions.length][];
				vertexCount += positions.length;
				long[] indices = primitive.has("indices")
						? readIndices(integer(primitive.get("indices"), -1)) : sequentialIndices(positions.length);
				int elements = indices.length;
				triangleCount += elements / 3;
				for (int positionIndex = 0; positionIndex < positions.length; positionIndex++) {
					double[] position = positions[positionIndex];
					if (!Double.isFinite(position[0]) || !Double.isFinite(position[1]) || !Double.isFinite(position[2])) {
						error("POSITION accessor " + accessor + " contains non-finite vertex");
						continue;
					}
					double[] modelPosition = apply(model, position);
					modelPositions[positionIndex] = modelPosition;
					minimumModelHeight = Math.min(minimumModelHeight, modelPosition[1]);
					maximumModelHeight = Math.max(maximumModelHeight, modelPosition[1]);
					double[] transformed = apply(world, position);
					if (!finite(transformed)) error("POSITION accessor " + accessor + " transforms to non-finite vertex");
					else validateBoundingVolume(transformed);
				}
				for (int i = 0; i + 2 < indices.length; i += 3) {
					int a = safeIndex(indices[i], modelPositions.length);
					int b = safeIndex(indices[i + 1], modelPositions.length);
					int c = safeIndex(indices[i + 2], modelPositions.length);
					if (a < 0 || b < 0 || c < 0 || modelPositions[a] == null
							|| modelPositions[b] == null || modelPositions[c] == null) continue;
					if (isSlopedSurface(modelPositions[a], modelPositions[b], modelPositions[c])) {
						slopedSurfaceTriangleCount++;
					}
				}
			}
		}

		private void validateBoundingVolume(double[] ecef) {
			double[] geodetic = ecefToGeodetic(ecef[0], ecef[1], ecef[2]);
			minimumLongitude = Math.min(minimumLongitude, geodetic[0]);
			minimumLatitude = Math.min(minimumLatitude, geodetic[1]);
			maximumLongitude = Math.max(maximumLongitude, geodetic[0]);
			maximumLatitude = Math.max(maximumLatitude, geodetic[1]);
			minimumEllipsoidHeight = Math.min(minimumEllipsoidHeight, geodetic[2]);
			maximumEllipsoidHeight = Math.max(maximumEllipsoidHeight, geodetic[2]);
			if (boundingVolume == null || !boundingVolume.has("region")) return;
			JsonArray region = array(boundingVolume.get("region"));
			if (region.size() != 6) return;
			double horizontalTolerance = 1.0 / WGS84_A;
			double heightTolerance = 1.0;
			double west = region.get(0).getAsDouble();
			double south = region.get(1).getAsDouble();
			double east = region.get(2).getAsDouble();
			double north = region.get(3).getAsDouble();
			double minimumHeight = region.get(4).getAsDouble();
			double maximumHeight = region.get(5).getAsDouble();
			boolean longitudeOutside = geodetic[0] < west - horizontalTolerance
					|| geodetic[0] > east + horizontalTolerance;
			boolean latitudeOutside = geodetic[1] < south - horizontalTolerance
					|| geodetic[1] > north + horizontalTolerance;
			boolean heightOutside = geodetic[2] < minimumHeight - heightTolerance
					|| geodetic[2] > maximumHeight + heightTolerance;
			if (longitudeOutside || latitudeOutside || heightOutside) {
				error("transformed POSITION lies outside boundingVolume.region: lon=" + geodetic[0]
						+ ", lat=" + geodetic[1] + ", height=" + geodetic[2]
						+ ", outside=" + (longitudeOutside ? "longitude " : "")
						+ (latitudeOutside ? "latitude " : "") + (heightOutside ? "height" : "")
						+ ", region=[" + west + ", " + south + ", " + east + ", " + north
						+ ", " + minimumHeight + ", " + maximumHeight + "]");
			}
		}

		private double[][] readVectors(int accessorIndex, int expectedComponents) {
			JsonArray accessors = array(gltf.get("accessors"));
			if (accessorIndex < 0 || accessorIndex >= accessors.size()) return new double[0][0];
			JsonObject accessor = object(accessors.get(accessorIndex));
			int count = integer(accessor.get("count"), 0);
			int components = componentCount(string(accessor.get("type")));
			int type = integer(accessor.get("componentType"), -1);
			if (count <= 0 || components != expectedComponents || componentSize(type) == 0) return new double[0][0];
			double[][] result = new double[count][components];
			if (accessor.has("bufferView")) {
				for (int i = 0; i < count; i++) for (int c = 0; c < components; c++) {
					result[i][c] = readAccessorComponent(accessor, i, c);
				}
			}
			applySparse(accessor, result);
			return result;
		}

		private long[] readIndices(int accessorIndex) {
			JsonArray accessors = array(gltf.get("accessors"));
			if (accessorIndex < 0 || accessorIndex >= accessors.size()) return new long[0];
			JsonObject accessor = object(accessors.get(accessorIndex));
			int count = integer(accessor.get("count"), 0);
			long[] result = new long[Math.max(0, count)];
			if (accessor.has("bufferView")) for (int i = 0; i < result.length; i++) {
				result[i] = (long)readAccessorComponent(accessor, i, 0);
			}
			if (accessor.has("sparse")) {
				JsonObject sparse = object(accessor.get("sparse"));
				int sparseCount = integer(sparse.get("count"), 0);
				long[] positions = readSparseIndices(sparse, sparseCount);
				JsonObject values = object(sparse.get("values"));
				for (int i = 0; i < sparseCount && i < positions.length; i++) {
					long target = positions[i];
					if (target >= 0 && target < result.length) result[(int)target] =
							(long)readRawComponent(values, i, 0, integer(accessor.get("componentType"), -1), 1,
									accessor.get("normalized") != null && accessor.get("normalized").getAsBoolean());
				}
			}
			return result;
		}

		private void applySparse(JsonObject accessor, double[][] result) {
			if (!accessor.has("sparse")) return;
			JsonObject sparse = object(accessor.get("sparse"));
			int sparseCount = integer(sparse.get("count"), 0);
			long[] positions = readSparseIndices(sparse, sparseCount);
			JsonObject values = object(sparse.get("values"));
			int type = integer(accessor.get("componentType"), -1);
			boolean normalized = accessor.get("normalized") != null && accessor.get("normalized").getAsBoolean();
			for (int i = 0; i < sparseCount && i < positions.length; i++) {
				long target = positions[i];
				if (target < 0 || target >= result.length) {
					error("sparse accessor index is outside accessor count: " + target);
					continue;
				}
				for (int component = 0; component < result[(int)target].length; component++) {
					result[(int)target][component] = readRawComponent(values, i, component, type,
							result[(int)target].length, normalized);
				}
			}
		}

		private long[] readSparseIndices(JsonObject sparse, int count) {
			JsonObject indices = object(sparse.get("indices"));
			int type = integer(indices.get("componentType"), -1);
			long[] result = new long[Math.max(0, count)];
			long previous = -1;
			for (int i = 0; i < result.length; i++) {
				result[i] = (long)readRawComponent(indices, i, 0, type, 1, false);
				if (result[i] <= previous) error("sparse accessor indices must be strictly increasing");
				previous = result[i];
			}
			return result;
		}

		private double readAccessorComponent(JsonObject accessor, int element, int component) {
			JsonObject reference = new JsonObject();
			reference.add("bufferView", accessor.get("bufferView"));
			reference.addProperty("byteOffset", integer(accessor.get("byteOffset"), 0));
			return readRawComponent(reference, element, component,
					integer(accessor.get("componentType"), -1),
					componentCount(string(accessor.get("type"))),
					accessor.get("normalized") != null && accessor.get("normalized").getAsBoolean());
		}

		private double readRawComponent(JsonObject reference, int element, int component,
				int componentType, int components, boolean normalized) {
			JsonArray views = array(gltf.get("bufferViews"));
			int viewIndex = integer(reference.get("bufferView"), -1);
			if (viewIndex < 0 || viewIndex >= views.size()) return Double.NaN;
			JsonObject view = object(views.get(viewIndex));
			int bufferIndex = integer(view.get("buffer"), -1);
			if (bufferIndex < 0 || bufferIndex >= buffers.size()) return Double.NaN;
			int size = componentSize(componentType);
			int stride = integer(view.get("byteStride"), components * size);
			long offset = (long)integer(view.get("byteOffset"), 0)
					+ integer(reference.get("byteOffset"), 0) + (long)element * stride + (long)component * size;
			byte[] data = buffers.get(bufferIndex);
			if (size == 0 || offset < 0 || offset + size > data.length) return Double.NaN;
			ByteBuffer bytes = ByteBuffer.wrap(data, (int)offset, size).order(LITTLE_ENDIAN);
			return switch (componentType) {
				case 5120 -> normalized ? Math.max(bytes.get() / 127.0, -1.0) : bytes.get();
				case 5121 -> normalized ? Byte.toUnsignedInt(bytes.get()) / 255.0 : Byte.toUnsignedInt(bytes.get());
				case 5122 -> normalized ? Math.max(bytes.getShort() / 32767.0, -1.0) : bytes.getShort();
				case 5123 -> normalized ? Short.toUnsignedInt(bytes.getShort()) / 65535.0
						: Short.toUnsignedInt(bytes.getShort());
				case 5125 -> Integer.toUnsignedLong(bytes.getInt());
				case 5126 -> bytes.getFloat();
				default -> Double.NaN;
			};
		}

		private byte[] sliceView(int viewIndex) {
			JsonObject view = object(array(gltf.get("bufferViews")).get(viewIndex));
			int buffer = integer(view.get("buffer"), -1);
			int offset = integer(view.get("byteOffset"), 0);
			int length = integer(view.get("byteLength"), 0);
			if (buffer < 0 || buffer >= buffers.size() || offset < 0 || length <= 0
					|| (long)offset + length > buffers.get(buffer).length) return new byte[0];
			byte[] result = new byte[length];
			System.arraycopy(buffers.get(buffer), offset, result, 0, length);
			return result;
		}

		private void error(String message) {
			if (errors.size() < 256) errors.add(file + ": " + message);
		}
	}

	private static long[] sequentialIndices(int count) {
		long[] result = new long[count];
		for (int i = 0; i < count; i++) result[i] = i;
		return result;
	}

	private static int safeIndex(long value, int size) {
		return value >= 0 && value < size ? (int)value : -1;
	}

	private static boolean isSlopedSurface(double[] a, double[] b, double[] c) {
		double abx = b[0] - a[0], aby = b[1] - a[1], abz = b[2] - a[2];
		double acx = c[0] - a[0], acy = c[1] - a[1], acz = c[2] - a[2];
		double nx = aby * acz - abz * acy;
		double ny = abz * acx - abx * acz;
		double nz = abx * acy - aby * acx;
		double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
		double minimum = Math.min(a[1], Math.min(b[1], c[1]));
		double maximum = Math.max(a[1], Math.max(b[1], c[1]));
		return length > 1e-12 && Math.abs(ny) / length > 0.1 && maximum - minimum > 0.05;
	}

	private static double[] nodeTransform(JsonObject node) {
		if (node.has("matrix")) {
			JsonArray matrix = array(node.get("matrix"));
			if (matrix.size() != 16) return identity();
			double[] result = new double[16];
			for (int i = 0; i < result.length; i++) result[i] = matrix.get(i).getAsDouble();
			return result;
		}
		double[] translation = vector(node.get("translation"), new double[] {0, 0, 0});
		double[] rotation = vector(node.get("rotation"), new double[] {0, 0, 0, 1});
		double[] scale = vector(node.get("scale"), new double[] {1, 1, 1});
		double x = rotation[0], y = rotation[1], z = rotation[2], w = rotation[3];
		return new double[] {
				(1 - 2*y*y - 2*z*z) * scale[0], (2*x*y + 2*z*w) * scale[0],
				(2*x*z - 2*y*w) * scale[0], 0,
				(2*x*y - 2*z*w) * scale[1], (1 - 2*x*x - 2*z*z) * scale[1],
				(2*y*z + 2*x*w) * scale[1], 0,
				(2*x*z + 2*y*w) * scale[2], (2*y*z - 2*x*w) * scale[2],
				(1 - 2*x*x - 2*y*y) * scale[2], 0,
				translation[0], translation[1], translation[2], 1};
	}

	static double[] identity() {
		return new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
	}

	static double[] multiply(double[] left, double[] right) {
		double[] result = new double[16];
		for (int column = 0; column < 4; column++) for (int row = 0; row < 4; row++) {
			for (int index = 0; index < 4; index++) {
				result[column * 4 + row] += left[index * 4 + row] * right[column * 4 + index];
			}
		}
		return result;
	}

	static double[] matrix(JsonElement element) {
		JsonArray values = array(element);
		if (values.size() != 16) return identity();
		double[] result = new double[16];
		for (int i = 0; i < 16; i++) result[i] = values.get(i).getAsDouble();
		return result;
	}

	private static double[] apply(double[] matrix, double[] point) {
		return new double[] {
				matrix[0]*point[0] + matrix[4]*point[1] + matrix[8]*point[2] + matrix[12],
				matrix[1]*point[0] + matrix[5]*point[1] + matrix[9]*point[2] + matrix[13],
				matrix[2]*point[0] + matrix[6]*point[1] + matrix[10]*point[2] + matrix[14]};
	}

	private static double[] ecefToGeodetic(double x, double y, double z) {
		double longitude = Math.atan2(y, x);
		double p = Math.hypot(x, y);
		double theta = Math.atan2(z * WGS84_A, p * WGS84_B);
		double sin = Math.sin(theta);
		double cos = Math.cos(theta);
		double latitude = Math.atan2(z + WGS84_EP2 * WGS84_B * sin * sin * sin,
				p - WGS84_E2 * WGS84_A * cos * cos * cos);
		double normal = WGS84_A / Math.sqrt(1 - WGS84_E2 * Math.sin(latitude) * Math.sin(latitude));
		double height = Math.abs(Math.cos(latitude)) < 1e-12
				? Math.abs(z) - WGS84_B : p / Math.cos(latitude) - normal;
		return new double[] {longitude, latitude, height};
	}

	private static int componentCount(String type) {
		if (type == null) return 0;
		return switch (type) {
			case "SCALAR" -> 1;
			case "VEC2" -> 2;
			case "VEC3" -> 3;
			case "VEC4", "MAT2" -> 4;
			case "MAT3" -> 9;
			case "MAT4" -> 16;
			default -> 0;
		};
	}

	private static int componentSize(int type) {
		return switch (type) {
			case 5120, 5121 -> 1;
			case 5122, 5123 -> 2;
			case 5125, 5126 -> 4;
			default -> 0;
		};
	}

	private static double[] vector(JsonElement value, double[] fallback) {
		JsonArray array = array(value);
		if (array.size() != fallback.length) return fallback;
		double[] result = new double[fallback.length];
		for (int i = 0; i < result.length; i++) result[i] = array.get(i).getAsDouble();
		return result;
	}

	private static boolean finite(double[] values) {
		for (double value : values) if (!Double.isFinite(value)) return false;
		return true;
	}

	private static JsonArray array(JsonElement element) {
		return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
	}

	private static JsonObject object(JsonElement element) {
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
	}

	private static String string(JsonElement element) {
		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}

	private static int integer(JsonElement element, int fallback) {
		try { return element == null ? fallback : element.getAsInt(); }
		catch (RuntimeException exception) { return fallback; }
	}

	record Result(long vertexCount, long triangleCount, long slopedSurfaceTriangleCount,
			double minimumModelHeight, double maximumModelHeight,
			double minimumLongitude, double minimumLatitude, double maximumLongitude, double maximumLatitude,
			double minimumEllipsoidHeight, double maximumEllipsoidHeight, List<String> errors) {
		static Result empty(List<String> errors) {
			return new Result(0, 0, 0, Double.NaN, Double.NaN,
					Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
					List.copyOf(errors));
		}

		double[] geodeticBounds() {
			return new double[] {minimumLongitude, minimumLatitude, maximumLongitude, maximumLatitude,
					minimumEllipsoidHeight, maximumEllipsoidHeight};
		}
	}
	private record Document(JsonObject json, byte[] bin) {}
}
