package org.osm2world.buildingtiler.tiles;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Reads the stable feature-part references intentionally embedded into final GLB node metadata. */
public final class FinalGlbFeatureIndex {

	private static final String PART_TAG = "ref:vector2world:part";

	public Set<String> readPartIds(Path glb) throws IOException {
		JsonObject root = readJson(glb);
		Set<String> result = new LinkedHashSet<>();
		for (JsonElement nodeElement : array(root.get("nodes"))) {
			JsonObject extras = object(object(nodeElement).get("extras"));
			JsonObject tags = object(extras.get("osmTags"));
			String partId = string(tags.get(PART_TAG));
			if (partId != null && !partId.isBlank() && !result.add(partId)) {
				throw new IOException("FINAL_GLTF_DUPLICATE_PART: duplicate part metadata " + partId + " in " + glb);
			}
		}
		return Set.copyOf(result);
	}

	private static JsonObject readJson(Path glb) throws IOException {
		try (FileChannel input = FileChannel.open(glb, StandardOpenOption.READ)) {
			long size = input.size();
			if (size < 20) throw new IOException("FINAL_GLTF_INVALID: GLB is too short: " + glb);
			ByteBuffer header = ByteBuffer.allocate(20).order(LITTLE_ENDIAN);
			readFully(input, header, glb);
			header.flip();
			if (header.getInt() != 0x46546c67 || header.getInt() != 2
					|| Integer.toUnsignedLong(header.getInt()) != size) {
				throw new IOException("FINAL_GLTF_INVALID: invalid GLB header: " + glb);
			}
			long jsonLength = Integer.toUnsignedLong(header.getInt());
			if (header.getInt() != 0x4e4f534a || jsonLength > size - 20 || jsonLength > Integer.MAX_VALUE) {
				throw new IOException("FINAL_GLTF_INVALID: invalid GLB JSON chunk: " + glb);
			}
			ByteBuffer json = ByteBuffer.allocate((int)jsonLength);
			readFully(input, json, glb);
			try {
				return JsonParser.parseString(new String(json.array(), UTF_8).trim()).getAsJsonObject();
			} catch (RuntimeException exception) {
				throw new IOException("FINAL_GLTF_INVALID: invalid GLB JSON: " + glb, exception);
			}
		}
	}

	private static void readFully(FileChannel input, ByteBuffer target, Path glb) throws IOException {
		while (target.hasRemaining()) {
			if (input.read(target) < 0) throw new IOException("FINAL_GLTF_INVALID: truncated GLB: " + glb);
		}
	}

	/**
	 * Verifies that no final triangle spans a point known to be inside an input polygon hole.
	 * Coordinates use glTF's local horizontal X/Z axes after the source projection has been applied.
	 */
	public void verifyOpenHoles(Path glb, Map<String, List<HorizontalPoint>> expectedHoles) throws IOException {
		if (expectedHoles == null || expectedHoles.isEmpty()) return;
		Glb document = read(glb);
		JsonObject root = document.json();
		Map<String, boolean[]> covered = new HashMap<>();
		expectedHoles.forEach((part, points) -> covered.put(part, new boolean[points.size()]));
		JsonArray nodes = array(root.get("nodes"));
		Set<Integer> children = new LinkedHashSet<>();
		for (JsonElement nodeElement : nodes) {
			for (JsonElement child : array(object(nodeElement).get("children"))) children.add(integer(child, -1));
		}
		for (int index = 0; index < nodes.size(); index++) {
			if (!children.contains(index)) visitNode(index, identity(), null, nodes, root, document.bin(),
					expectedHoles, covered, new LinkedHashSet<>());
		}
		for (var entry : covered.entrySet()) {
			boolean[] values = entry.getValue();
			for (int hole = 0; hole < values.length; hole++) {
				if (values[hole]) throw new IOException("FINAL_GLTF_HOLE_FILLED: part " + entry.getKey()
						+ " has a final triangle spanning source hole " + hole + " in " + glb);
			}
		}
	}

	/** Validates the actual vertex height range for every modeled source part. */
	public void verifyPartHeights(Path glb, Map<String, Double> expectedHeightsMeters) throws IOException {
		if (expectedHeightsMeters == null || expectedHeightsMeters.isEmpty()) return;
		Glb document = read(glb);
		JsonObject root = document.json();
		Map<String, double[]> ranges = new HashMap<>();
		expectedHeightsMeters.keySet().forEach(part -> ranges.put(part,
				new double[] {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}));
		JsonArray nodes = array(root.get("nodes"));
		Set<Integer> children = new LinkedHashSet<>();
		for (JsonElement nodeElement : nodes) {
			for (JsonElement child : array(object(nodeElement).get("children"))) children.add(integer(child, -1));
		}
		for (int index = 0; index < nodes.size(); index++) {
			if (!children.contains(index)) visitHeightNode(index, identity(), null, nodes, root,
					document.bin(), ranges, new LinkedHashSet<>());
		}
		for (var entry : expectedHeightsMeters.entrySet()) {
			double[] range = ranges.get(entry.getKey());
			if (!Double.isFinite(range[0]) || !Double.isFinite(range[1])) {
				throw new IOException("FINAL_GLTF_PART_WITHOUT_VERTICES: " + entry.getKey() + " in " + glb);
			}
			double actual = range[1] - range[0];
			double tolerance = Math.max(0.02, entry.getValue() * 1e-4);
			if (Math.abs(actual - entry.getValue()) > tolerance) {
				throw new IOException("FINAL_GLTF_HEIGHT_MISMATCH: part " + entry.getKey()
						+ " expected " + entry.getValue() + "m but final vertices span " + actual + "m in " + glb);
			}
		}
	}

	private static void visitHeightNode(int index, double[] parentTransform, String inheritedPart,
			JsonArray nodes, JsonObject root, byte[] bin, Map<String, double[]> ranges,
			Set<Integer> path) throws IOException {
		if (index < 0 || index >= nodes.size() || !path.add(index)) {
			throw new IOException("FINAL_GLTF_INVALID: node graph contains an invalid reference or cycle");
		}
		JsonObject node = object(nodes.get(index));
		String part = string(object(object(node.get("extras")).get("osmTags")).get(PART_TAG));
		if (part == null || part.isBlank()) part = inheritedPart;
		double[] transform = multiply(parentTransform, nodeTransform(node));
		int meshIndex = integer(node.get("mesh"), -1);
		if (part != null && ranges.containsKey(part) && meshIndex >= 0) {
			double[] range = ranges.get(part);
			for (double[] position : meshPositions(meshIndex, transform, root, bin)) {
				range[0] = Math.min(range[0], position[1]);
				range[1] = Math.max(range[1], position[1]);
			}
		}
		for (JsonElement child : array(node.get("children"))) {
			visitHeightNode(integer(child, -1), transform, part, nodes, root, bin, ranges,
					new LinkedHashSet<>(path));
		}
	}

	private static List<double[]> meshPositions(int meshIndex, double[] transform,
			JsonObject root, byte[] bin) throws IOException {
		JsonArray meshes = array(root.get("meshes"));
		if (meshIndex < 0 || meshIndex >= meshes.size()) throw new IOException("FINAL_GLTF_INVALID: invalid mesh reference");
		List<double[]> result = new java.util.ArrayList<>();
		for (JsonElement primitiveElement : array(object(meshes.get(meshIndex)).get("primitives"))) {
			JsonObject attributes = object(object(primitiveElement).get("attributes"));
			int positionAccessor = integer(attributes.get("POSITION"), -1);
			if (positionAccessor >= 0) result.addAll(List.of(positions(root, bin, positionAccessor, transform)));
		}
		return result;
	}

	private static Glb read(Path glb) throws IOException {
		byte[] bytes = Files.readAllBytes(glb);
		if (bytes.length < 20) throw new IOException("FINAL_GLTF_INVALID: GLB is too short: " + glb);
		ByteBuffer buffer = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN);
		if (buffer.getInt() != 0x46546c67 || buffer.getInt() != 2
				|| Integer.toUnsignedLong(buffer.getInt()) != bytes.length) {
			throw new IOException("FINAL_GLTF_INVALID: invalid GLB header: " + glb);
		}
		long jsonLength = Integer.toUnsignedLong(buffer.getInt());
		if (buffer.getInt() != 0x4e4f534a || jsonLength > buffer.remaining() || jsonLength > Integer.MAX_VALUE) {
			throw new IOException("FINAL_GLTF_INVALID: invalid GLB JSON chunk: " + glb);
		}
		byte[] json = new byte[(int)jsonLength];
		buffer.get(json);
		JsonObject root;
		try { root = JsonParser.parseString(new String(json, UTF_8).trim()).getAsJsonObject(); }
		catch (RuntimeException exception) {
			throw new IOException("FINAL_GLTF_INVALID: invalid GLB JSON: " + glb, exception);
		}
		byte[] bin = new byte[0];
		if (buffer.remaining() >= 8) {
			long binLength = Integer.toUnsignedLong(buffer.getInt());
			int binType = buffer.getInt();
			if (binType != 0x004e4942 || binLength > buffer.remaining() || binLength > Integer.MAX_VALUE) {
				throw new IOException("FINAL_GLTF_INVALID: invalid GLB BIN chunk: " + glb);
			}
			bin = new byte[(int)binLength];
			buffer.get(bin);
		}
		return new Glb(root, bin);
	}

	private static void visitNode(int index, double[] parentTransform, String inheritedPart,
			JsonArray nodes, JsonObject root, byte[] bin,
			Map<String, List<HorizontalPoint>> expected, Map<String, boolean[]> covered,
			Set<Integer> path) throws IOException {
		if (index < 0 || index >= nodes.size() || !path.add(index)) {
			throw new IOException("FINAL_GLTF_INVALID: node graph contains an invalid reference or cycle");
		}
		JsonObject node = object(nodes.get(index));
		String part = string(object(object(node.get("extras")).get("osmTags")).get(PART_TAG));
		if (part == null || part.isBlank()) part = inheritedPart;
		double[] transform = multiply(parentTransform, nodeTransform(node));
		int meshIndex = integer(node.get("mesh"), -1);
		if (part != null && expected.containsKey(part) && meshIndex >= 0) {
			checkMesh(meshIndex, transform, expected.get(part), covered.get(part), root, bin);
		}
		for (JsonElement child : array(node.get("children"))) {
			visitNode(integer(child, -1), transform, part, nodes, root, bin, expected, covered,
					new LinkedHashSet<>(path));
		}
	}

	private static void checkMesh(int meshIndex, double[] transform, List<HorizontalPoint> points,
			boolean[] covered, JsonObject root, byte[] bin) throws IOException {
		JsonArray meshes = array(root.get("meshes"));
		if (meshIndex >= meshes.size()) throw new IOException("FINAL_GLTF_INVALID: invalid mesh reference");
		for (JsonElement primitiveElement : array(object(meshes.get(meshIndex)).get("primitives"))) {
			JsonObject primitive = object(primitiveElement);
			if (integer(primitive.get("mode"), 4) != 4) continue;
			int positionAccessor = integer(object(primitive.get("attributes")).get("POSITION"), -1);
			if (positionAccessor < 0) continue;
			double[][] positions = positions(root, bin, positionAccessor, transform);
			int[] indices = primitive.has("indices")
					? indices(root, bin, integer(primitive.get("indices"), -1))
					: java.util.stream.IntStream.range(0, positions.length).toArray();
			for (int offset = 0; offset + 2 < indices.length; offset += 3) {
				int ia = indices[offset], ib = indices[offset + 1], ic = indices[offset + 2];
				if (ia < 0 || ib < 0 || ic < 0 || ia >= positions.length
						|| ib >= positions.length || ic >= positions.length) {
					throw new IOException("FINAL_GLTF_INVALID: triangle index exceeds POSITION accessor");
				}
				double[] a = positions[ia], b = positions[ib], c = positions[ic];
				for (int pointIndex = 0; pointIndex < points.size(); pointIndex++) {
					if (!covered[pointIndex] && contains(points.get(pointIndex), a, b, c)) {
						covered[pointIndex] = true;
					}
				}
			}
		}
	}

	private static double[][] positions(JsonObject root, byte[] bin, int accessorIndex,
			double[] transform) throws IOException {
		Accessor accessor = accessor(root, bin, accessorIndex, 3);
		double[][] result = new double[accessor.count()][3];
		for (int index = 0; index < accessor.count(); index++) {
			double x = component(accessor, index, 0);
			double y = component(accessor, index, 1);
			double z = component(accessor, index, 2);
			double w = transform[3] * x + transform[7] * y + transform[11] * z + transform[15];
			if (Math.abs(w) < 1e-12) throw new IOException("FINAL_GLTF_INVALID: node transform produced w=0");
			result[index][0] = (transform[0] * x + transform[4] * y + transform[8] * z + transform[12]) / w;
			result[index][1] = (transform[1] * x + transform[5] * y + transform[9] * z + transform[13]) / w;
			result[index][2] = (transform[2] * x + transform[6] * y + transform[10] * z + transform[14]) / w;
		}
		return result;
	}

	private static int[] indices(JsonObject root, byte[] bin, int accessorIndex) throws IOException {
		Accessor accessor = accessor(root, bin, accessorIndex, 1);
		int[] result = new int[accessor.count()];
		for (int index = 0; index < result.length; index++) {
			double value = component(accessor, index, 0);
			if (value < 0 || value > Integer.MAX_VALUE || value != Math.rint(value)) {
				throw new IOException("FINAL_GLTF_INVALID: index accessor contains a non-integer value");
			}
			result[index] = (int)value;
		}
		return result;
	}

	private static Accessor accessor(JsonObject root, byte[] bin, int index, int expectedComponents)
			throws IOException {
		JsonArray accessors = array(root.get("accessors"));
		JsonArray views = array(root.get("bufferViews"));
		if (index < 0 || index >= accessors.size()) throw new IOException("FINAL_GLTF_INVALID: invalid accessor");
		JsonObject accessor = object(accessors.get(index));
		int components = switch (string(accessor.get("type"))) {
			case "SCALAR" -> 1;
			case "VEC2" -> 2;
			case "VEC3" -> 3;
			case "VEC4" -> 4;
			default -> 0;
		};
		if (components != expectedComponents || !accessor.has("bufferView") || accessor.has("sparse")) {
			throw new IOException("FINAL_GLTF_INVALID: unsupported accessor layout");
		}
		int viewIndex = integer(accessor.get("bufferView"), -1);
		if (viewIndex < 0 || viewIndex >= views.size()) throw new IOException("FINAL_GLTF_INVALID: invalid bufferView");
		JsonObject view = object(views.get(viewIndex));
		if (integer(view.get("buffer"), 0) != 0) throw new IOException("FINAL_GLTF_INVALID: external buffer is unsupported");
		int componentType = integer(accessor.get("componentType"), -1);
		int componentBytes = componentBytes(componentType);
		int count = integer(accessor.get("count"), -1);
		int stride = integer(view.get("byteStride"), componentBytes * components);
		int offset = integer(view.get("byteOffset"), 0) + integer(accessor.get("byteOffset"), 0);
		long end = (long)offset + (long)Math.max(0, count - 1) * stride + (long)components * componentBytes;
		if (componentBytes == 0 || count < 0 || stride < components * componentBytes
				|| offset < 0 || end > bin.length) {
			throw new IOException("FINAL_GLTF_INVALID: accessor exceeds BIN chunk");
		}
		return new Accessor(ByteBuffer.wrap(bin).order(LITTLE_ENDIAN), offset, stride, count,
				components, componentType, componentBytes, booleanValue(accessor.get("normalized")));
	}

	private static double component(Accessor accessor, int element, int component) {
		int offset = accessor.offset() + element * accessor.stride() + component * accessor.componentBytes();
		double value = switch (accessor.componentType()) {
			case 5120 -> accessor.data().get(offset);
			case 5121 -> Byte.toUnsignedInt(accessor.data().get(offset));
			case 5122 -> accessor.data().getShort(offset);
			case 5123 -> Short.toUnsignedInt(accessor.data().getShort(offset));
			case 5125 -> Integer.toUnsignedLong(accessor.data().getInt(offset));
			case 5126 -> accessor.data().getFloat(offset);
			default -> Double.NaN;
		};
		if (!accessor.normalized()) return value;
		return switch (accessor.componentType()) {
			case 5120 -> Math.max(-1, value / 127.0);
			case 5121 -> value / 255.0;
			case 5122 -> Math.max(-1, value / 32767.0);
			case 5123 -> value / 65535.0;
			case 5125 -> value / 4294967295.0;
			default -> value;
		};
	}

	private static boolean contains(HorizontalPoint point, double[] a, double[] b, double[] c) {
		double area = cross(a[0], a[2], b[0], b[2], c[0], c[2]);
		if (Math.abs(area) < 1e-9) return false;
		double w1 = cross(point.x(), point.z(), b[0], b[2], c[0], c[2]) / area;
		double w2 = cross(a[0], a[2], point.x(), point.z(), c[0], c[2]) / area;
		double w3 = cross(a[0], a[2], b[0], b[2], point.x(), point.z()) / area;
		return w1 >= -1e-8 && w2 >= -1e-8 && w3 >= -1e-8;
	}

	private static double cross(double ax, double az, double bx, double bz, double cx, double cz) {
		return (bx - ax) * (cz - az) - (bz - az) * (cx - ax);
	}

	private static double[] nodeTransform(JsonObject node) {
		JsonArray matrix = array(node.get("matrix"));
		if (matrix.size() == 16) {
			double[] result = new double[16];
			for (int index = 0; index < 16; index++) result[index] = matrix.get(index).getAsDouble();
			return result;
		}
		double[] translation = vector(node.get("translation"), new double[] {0, 0, 0});
		double[] scale = vector(node.get("scale"), new double[] {1, 1, 1});
		double[] q = vector(node.get("rotation"), new double[] {0, 0, 0, 1});
		double x = q[0], y = q[1], z = q[2], w = q[3];
		double[] result = identity();
		result[0] = (1 - 2 * (y * y + z * z)) * scale[0];
		result[1] = (2 * (x * y + z * w)) * scale[0];
		result[2] = (2 * (x * z - y * w)) * scale[0];
		result[4] = (2 * (x * y - z * w)) * scale[1];
		result[5] = (1 - 2 * (x * x + z * z)) * scale[1];
		result[6] = (2 * (y * z + x * w)) * scale[1];
		result[8] = (2 * (x * z + y * w)) * scale[2];
		result[9] = (2 * (y * z - x * w)) * scale[2];
		result[10] = (1 - 2 * (x * x + y * y)) * scale[2];
		result[12] = translation[0]; result[13] = translation[1]; result[14] = translation[2];
		return result;
	}

	private static double[] vector(JsonElement element, double[] fallback) {
		JsonArray values = array(element);
		if (values.size() != fallback.length) return fallback;
		double[] result = new double[fallback.length];
		for (int index = 0; index < result.length; index++) result[index] = values.get(index).getAsDouble();
		return result;
	}

	private static double[] identity() {
		return new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
	}

	private static double[] multiply(double[] left, double[] right) {
		double[] result = new double[16];
		for (int column = 0; column < 4; column++) for (int row = 0; row < 4; row++) {
			for (int inner = 0; inner < 4; inner++) {
				result[row + column * 4] += left[row + inner * 4] * right[inner + column * 4];
			}
		}
		return result;
	}

	private static int componentBytes(int componentType) {
		return switch (componentType) {
			case 5120, 5121 -> 1;
			case 5122, 5123 -> 2;
			case 5125, 5126 -> 4;
			default -> 0;
		};
	}

	public static String partTag() { return PART_TAG; }

	private static JsonArray array(JsonElement value) {
		return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
	}

	private static JsonObject object(JsonElement value) {
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
	}

	private static String string(JsonElement value) {
		return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
	}

	private static int integer(JsonElement value, int fallback) {
		try { return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback; }
		catch (RuntimeException exception) { return fallback; }
	}

	private static boolean booleanValue(JsonElement value) {
		return value != null && value.isJsonPrimitive() && value.getAsBoolean();
	}

	public record HorizontalPoint(double x, double z) {
		public HorizontalPoint {
			if (!Double.isFinite(x) || !Double.isFinite(z)) {
				throw new IllegalArgumentException("Horizontal point must be finite");
			}
		}
	}

	private record Glb(JsonObject json, byte[] bin) {}
	private record Accessor(ByteBuffer data, int offset, int stride, int count, int components,
			int componentType, int componentBytes, boolean normalized) {}
}
