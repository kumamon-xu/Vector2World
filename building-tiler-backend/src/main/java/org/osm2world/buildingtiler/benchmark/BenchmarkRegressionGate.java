package org.osm2world.buildingtiler.benchmark;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Relative regression gate; it intentionally contains no absolute PR timing. */
public final class BenchmarkRegressionGate {

	public static final double MAX_TIME_RATIO = 1.50;
	public static final double MAX_HEAP_RATIO = 1.25;
	public static final double MAX_OUTPUT_RATIO = 1.15;

	public GateResult evaluate(JsonObject baseline, JsonObject current, String exemptionReason) {
		Map<Integer, JsonObject> expected = summaries(baseline);
		Map<Integer, JsonObject> actual = summaries(current);
		List<String> violations = new ArrayList<>();
		for (Map.Entry<Integer, JsonObject> entry : expected.entrySet()) {
			JsonObject now = actual.get(entry.getKey());
			if (now == null) {
				violations.add(entry.getKey() + ": missing current summary");
				continue;
			}
			if (now.get("successfulRuns").getAsInt() < 3) {
				violations.add(entry.getKey() + ": fewer than 3 successful repetitions");
				continue;
			}
			compare(violations, entry.getKey(), "medianTotalMillis", entry.getValue(), now, MAX_TIME_RATIO);
			compare(violations, entry.getKey(), "medianPeakHeapBytes", entry.getValue(), now, MAX_HEAP_RATIO);
			long baselineOutput = number(entry.getValue(), "medianOutputBytes");
			if (baselineOutput > 0) compare(violations, entry.getKey(), "medianOutputBytes",
					entry.getValue(), now, MAX_OUTPUT_RATIO);
		}
		boolean exempted = exemptionReason != null && !exemptionReason.isBlank();
		return new GateResult(violations.isEmpty() || exempted, exempted,
				exempted ? exemptionReason.trim() : null, List.copyOf(violations));
	}

	private static void compare(List<String> violations, int size, String metric,
			JsonObject baseline, JsonObject current, double limit) {
		long before = number(baseline, metric);
		long after = number(current, metric);
		if (before <= 0) return;
		double ratio = (double)after / before;
		if (ratio > limit) violations.add(size + ": " + metric + " ratio " + ratio
				+ " exceeds " + limit + " (" + before + " -> " + after + ")");
	}

	private static long number(JsonObject object, String name) {
		return object.has(name) ? object.get(name).getAsLong() : 0;
	}

	private static Map<Integer, JsonObject> summaries(JsonObject report) {
		Map<Integer, JsonObject> result = new LinkedHashMap<>();
		JsonArray values = report.getAsJsonArray("summary");
		if (values == null) throw new IllegalArgumentException("Benchmark report has no summary array");
		values.forEach(value -> {
			JsonObject summary = value.getAsJsonObject();
			result.put(summary.get("featureCount").getAsInt(), summary);
		});
		return result;
	}

	public static void main(String[] args) throws IOException {
		Path baseline = null;
		Path current = null;
		Path output = Path.of("output/m5-benchmark/benchmark-gate.json");
		String exemption = null;
		for (String arg : args) {
			if (arg.startsWith("--baseline=")) baseline = Path.of(arg.substring(11));
			else if (arg.startsWith("--current=")) current = Path.of(arg.substring(10));
			else if (arg.startsWith("--output=")) output = Path.of(arg.substring(9));
			else if (arg.startsWith("--exemption=")) exemption = arg.substring(12);
			else throw new IllegalArgumentException("Unknown argument: " + arg);
		}
		if (baseline == null || current == null) {
			throw new IllegalArgumentException("--baseline and --current are required");
		}
		JsonObject baselineJson = JsonParser.parseString(Files.readString(baseline, UTF_8)).getAsJsonObject();
		JsonObject currentJson = JsonParser.parseString(Files.readString(current, UTF_8)).getAsJsonObject();
		GateResult result = new BenchmarkRegressionGate().evaluate(baselineJson, currentJson, exemption);
		Files.createDirectories(output.toAbsolutePath().getParent());
		Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
				"schemaVersion", "1.0", "evaluatedAt", Instant.now().toString(), "result", result)), UTF_8);
		if (!result.passed()) throw new IllegalStateException("Benchmark regression gate failed: " + result.violations());
		System.out.println(result.exempted() ? "Benchmark gate passed with recorded exemption" : "Benchmark gate passed");
	}

	public record GateResult(boolean passed, boolean exempted, String exemptionReason,
			List<String> violations) {}
}
