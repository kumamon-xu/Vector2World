package org.osm2world.buildingtiler.cli;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.osm2world.buildingtiler.application.Milestone0Pipeline;
import org.osm2world.buildingtiler.application.UpstreamBaseline;

import com.google.gson.GsonBuilder;

public final class Milestone0Cli {

	private Milestone0Cli() {}

	public static void main(String[] args) throws Exception {
		Map<String, String> options = parse(args);
		if (options.containsKey("help")) {
			printUsage();
			return;
		}

		Path input = requiredPath(options, "input");
		Path output = requiredPath(options, "output");
		String heightField = options.getOrDefault("height-field", "Elevation");
		int zoom = positiveInt(options, "zoom", 15);
		int maxTiles = positiveInt(options, "max-tiles", 2);
		int lodNumber = positiveInt(options, "lod", 4);
		boolean clipToBounds = Boolean.parseBoolean(options.getOrDefault("clip-to-bounds", "false"));
		if (lodNumber > 4) throw new IllegalArgumentException("--lod must be 1, 2, 3 or 4");

		long started = System.nanoTime();
		var result = new Milestone0Pipeline().run(
				input, output, heightField, zoom, lodNumber, maxTiles, clipToBounds);
		long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("status", "COMPLETED");
		summary.put("applicationVersion", UpstreamBaseline.APPLICATION_VERSION);
		summary.put("osm2worldVersion", UpstreamBaseline.OSM2WORLD_VERSION);
		summary.put("osm2worldCommit", UpstreamBaseline.OSM2WORLD_COMMIT);
		summary.put("input", input.toAbsolutePath().toString());
		summary.put("format", result.dataset().metadata().format());
		summary.put("sourceCrs", result.dataset().metadata().sourceCrs());
		summary.put("sourceEncoding", result.dataset().metadata().sourceEncoding());
		summary.put("inputFeatures", result.dataset().metadata().featureCount());
		summary.put("validBuildings", result.dataset().metadata().validBuildings());
		summary.put("modeledBuildings", result.generation().modeledBuildings());
		summary.put("clipToBounds", clipToBounds);
		summary.put("tiles", result.generation().tiles());
		summary.put("meshCount", result.generation().meshCount());
		summary.put("validation", result.generation().validation());
		summary.put("output", result.generation().outputDirectory().toString());
		summary.put("elapsedMillis", elapsedMillis);
		System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(summary));
	}

	private static Map<String, String> parse(String[] args) {
		Map<String, String> result = new LinkedHashMap<>();
		for (int i = 0; i < args.length; i++) {
			String argument = args[i];
			if ("--help".equals(argument) || "-h".equals(argument)) {
				result.put("help", "true");
				continue;
			}
			if (!argument.startsWith("--") || i + 1 >= args.length) {
				throw new IllegalArgumentException("Expected --key value, got: " + argument);
			}
			result.put(argument.substring(2), args[++i]);
		}
		return result;
	}

	private static Path requiredPath(Map<String, String> options, String key) {
		String value = options.get(key);
		if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing --" + key);
		return Path.of(value);
	}

	private static int positiveInt(Map<String, String> options, String key, int defaultValue) {
		int value = Integer.parseInt(options.getOrDefault(key, Integer.toString(defaultValue)));
		if (value <= 0) throw new IllegalArgumentException("--" + key + " must be positive");
		return value;
	}

	private static void printUsage() {
		System.out.println("""
				Vector2World Milestone 0 spike
				  --input <file.geojson|file.shp>
				  --output <new-directory>
				  [--height-field Elevation]
				  [--zoom 15]
				  [--lod 4]
				  [--max-tiles 2]
				  [--clip-to-bounds false]
				""");
	}

}
