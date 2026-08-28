package org.osm2world.buildingtiler.benchmark;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;

/** Rebuilds deterministic benchmark inputs; generated corpora stay outside Git. */
public final class BenchmarkCorpusGenerator {

	public static final String GENERATOR_VERSION = "m5-corpus-v3";

	public Corpus generate(Path outputDirectory, int featureCount) throws IOException {
		if (featureCount < 1) throw new IllegalArgumentException("featureCount must be positive");
		Files.createDirectories(outputDirectory);
		Path geoJson = outputDirectory.resolve("buildings-" + featureCount + ".geojson");
		try (BufferedWriter writer = Files.newBufferedWriter(geoJson, UTF_8)) {
			writer.write("{\"type\":\"FeatureCollection\",\"name\":\"vector2world-m5-"
					+ featureCount + "\",\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"OGC:CRS84\"}},\"features\":[");
			for (int index = 0; index < featureCount; index++) {
				if (index > 0) writer.write(',');
				writeFeature(writer, index);
			}
			writer.write("]}");
		}

		Map<String, Integer> scenarios = scenarioCounts(featureCount);
		String checksum = sha256(geoJson);
		Map<String, Object> manifest = new LinkedHashMap<>();
		manifest.put("schemaVersion", "1.0");
		manifest.put("generatorVersion", GENERATOR_VERSION);
		manifest.put("generatedAt", Instant.now().toString());
		manifest.put("license", "CC0-1.0 synthetic benchmark data");
		manifest.put("crs", "OGC:CRS84");
		manifest.put("heightField", "Elevation");
		manifest.put("heightUnit", "m");
		manifest.put("featureCount", featureCount);
		manifest.put("scenarioCounts", scenarios);
		manifest.put("file", geoJson.getFileName().toString());
		manifest.put("bytes", Files.size(geoJson));
		manifest.put("sha256", checksum);
		Path manifestFile = outputDirectory.resolve("buildings-" + featureCount + ".manifest.json");
		Files.writeString(manifestFile,
				new GsonBuilder().setPrettyPrinting().create().toJson(manifest), UTF_8);
		return new Corpus(geoJson, manifestFile, featureCount, Files.size(geoJson), checksum, scenarios);
	}

	private static void writeFeature(BufferedWriter writer, int index) throws IOException {
		int column = index % 400;
		int row = index / 400;
		long x = 116_200_000L + column * 260L;
		long y = 39_800_000L + row * 220L;
		long width = index % 97 == 0 ? 700L : 120L + index % 5 * 12L;
		long depth = index % 97 == 0 ? 520L : 100L + index % 7 * 10L;
		String scenario = scenario(index);
		writer.write("{\"type\":\"Feature\",\"id\":\"b-");
		writer.write(Integer.toString(index));
		writer.write("\",\"properties\":{\"Elevation\":");
		writer.write(Integer.toString(6 + index % 55 * 3));
		writer.write(",\"scenario\":\"");
		writer.write(scenario);
		writer.write("\",\"building:material\":\"");
		writer.write(index % 3 == 0 ? "brick" : index % 3 == 1 ? "concrete" : "glass");
		writer.write('"');
		if ("wide-attributes".equals(scenario)) {
			for (int attribute = 0; attribute < 32; attribute++) {
				writer.write(",\"source_attribute_");
				writer.write(Integer.toString(attribute));
				writer.write("\":\"value-");
				writer.write(Integer.toString(index % 1000));
				writer.write('-');
				writer.write(Integer.toString(attribute));
				writer.write('"');
			}
		}
		writer.write("},\"geometry\":");
		if ("holes".equals(scenario)) writePolygonWithHole(writer, x, y, width, depth);
		else if ("multipolygon".equals(scenario)) writeMultiPolygon(writer, x, y, width, depth);
		else if ("complex".equals(scenario)) writeComplexPolygon(writer, x, y, width, depth);
		else writeRectangle(writer, x, y, width, depth);
		writer.write('}');
	}

	private static void writeRectangle(BufferedWriter writer, long x, long y, long width, long depth)
			throws IOException {
		writer.write("{\"type\":\"Polygon\",\"coordinates\":[[");
		writeRing(writer, new long[][] {{x,y},{x+width,y},{x+width,y+depth},{x,y+depth},{x,y}});
		writer.write("]]}");
	}

	private static void writePolygonWithHole(BufferedWriter writer, long x, long y, long width, long depth)
			throws IOException {
		writer.write("{\"type\":\"Polygon\",\"coordinates\":[[");
		writeRing(writer, new long[][] {{x,y},{x+width,y},{x+width,y+depth},{x,y+depth},{x,y}});
		writer.write("],[");
		long insetX = Math.max(20, width / 4);
		long insetY = Math.max(20, depth / 4);
		writeRing(writer, new long[][] {{x+insetX,y+insetY},{x+insetX,y+depth-insetY},
				{x+width-insetX,y+depth-insetY},{x+width-insetX,y+insetY},{x+insetX,y+insetY}});
		writer.write("]]}");
	}

	private static void writeMultiPolygon(BufferedWriter writer, long x, long y, long width, long depth)
			throws IOException {
		long half = Math.max(30, width / 3);
		writer.write("{\"type\":\"MultiPolygon\",\"coordinates\":[[[");
		writeRing(writer, new long[][] {{x,y},{x+half,y},{x+half,y+depth},{x,y+depth},{x,y}});
		writer.write("]],[[");
		writeRing(writer, new long[][] {{x+width-half,y},{x+width,y},{x+width,y+depth},
				{x+width-half,y+depth},{x+width-half,y}});
		writer.write("]]]}");
	}

	private static void writeComplexPolygon(BufferedWriter writer, long x, long y, long width, long depth)
			throws IOException {
		long a = width / 3;
		long b = depth / 3;
		writer.write("{\"type\":\"Polygon\",\"coordinates\":[[");
		writeRing(writer, new long[][] {{x+a,y},{x+width-a,y},{x+width,y+b},{x+width,y+depth-b},
				{x+width-a,y+depth},{x+a,y+depth},{x,y+depth-b},{x,y+b},{x+a,y}});
		writer.write("]]}");
	}

	private static void writeRing(BufferedWriter writer, long[][] points) throws IOException {
		for (int index = 0; index < points.length; index++) {
			if (index > 0) writer.write(',');
			writer.write('[');
			writer.write(decimal(points[index][0]));
			writer.write(',');
			writer.write(decimal(points[index][1]));
			writer.write(']');
		}
	}

	private static String decimal(long microDegrees) {
		long whole = Math.floorDiv(microDegrees, 1_000_000L);
		long fraction = Math.floorMod(microDegrees, 1_000_000L);
		String digits = Long.toString(fraction);
		return whole + "." + "000000".substring(digits.length()) + digits;
	}

	private static String scenario(int index) {
		if (index % 101 == 0) return "multipolygon";
		if (index % 43 == 0) return "holes";
		if (index % 17 == 0) return "complex";
		if (index % 97 == 0) return "cross-tile";
		if (index % 13 == 0) return "wide-attributes";
		return "simple";
	}

	private static Map<String, Integer> scenarioCounts(int count) {
		Map<String, Integer> result = new LinkedHashMap<>();
		for (int index = 0; index < count; index++) result.merge(scenario(index), 1, Integer::sum);
		return Map.copyOf(result);
	}

	private static String sha256(Path file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = Files.newInputStream(file)) {
				byte[] buffer = new byte[64 * 1024];
				for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is required by Java", exception);
		}
	}

	public static void main(String[] args) throws Exception {
		Path output = Path.of("output/m5-benchmark/corpus");
		List<Integer> sizes = new ArrayList<>(List.of(1_000, 10_000, 100_000));
		for (String arg : args) {
			if (arg.startsWith("--output=")) output = Path.of(arg.substring("--output=".length()));
			else if (arg.startsWith("--sizes=")) sizes = java.util.Arrays.stream(
					arg.substring("--sizes=".length()).split(",")).map(Integer::parseInt).toList();
		}
		BenchmarkCorpusGenerator generator = new BenchmarkCorpusGenerator();
		for (int size : sizes) System.out.println(generator.generate(output, size));
	}

	public record Corpus(Path geoJson, Path manifest, int featureCount, long bytes,
			String sha256, Map<String, Integer> scenarioCounts) {}
}
