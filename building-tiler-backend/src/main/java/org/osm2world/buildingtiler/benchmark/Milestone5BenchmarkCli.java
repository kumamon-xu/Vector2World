package org.osm2world.buildingtiler.benchmark;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.osm2world.buildingtiler.application.DatasetService;
import org.osm2world.buildingtiler.application.GenerationJobService;
import org.osm2world.buildingtiler.application.GenerationJobSpec;
import org.osm2world.buildingtiler.application.GenerationJobState;
import org.osm2world.buildingtiler.application.ManagedDataset;
import org.osm2world.buildingtiler.application.ManagedGenerationJob;
import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.domain.HeightUnit;
import org.osm2world.buildingtiler.domain.InvalidHeightPolicy;
import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.domain.TilingConfig;
import org.osm2world.buildingtiler.gis.ImportOptions;
import org.osm2world.buildingtiler.gis.UploadLimits;
import org.osm2world.buildingtiler.modeling.BuildingRuleEngine;
import org.osm2world.buildingtiler.modeling.OsmTagMapper;
import org.osm2world.buildingtiler.osm2world.Osm2WorldEngineAdapter;
import org.osm2world.buildingtiler.tiles.Osm2WorldTileRenderer;
import org.osm2world.buildingtiler.tiles.TileOwnershipPlanner;
import org.osm2world.buildingtiler.tiles.TilesetTreeAssembler;
import org.osm2world.buildingtiler.tiles.TilesetValidator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reproducible M5 harness. Analysis mode measures input/rules/tiling at all
 * scales; --full-up-to runs the complete OSM2World/GLB/3D Tiles pipeline for
 * every size at or below that value.
 */
public final class Milestone5BenchmarkCli {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final HeightMapping HEIGHT = new HeightMapping("Elevation", HeightUnit.M,
			InvalidHeightPolicy.FAIL, 10_000);
	private static final UploadLimits BENCHMARK_UPLOAD_LIMITS = new UploadLimits(
			2L * 1024 * 1024 * 1024, 4L * 1024 * 1024 * 1024, 20_000, 200.0, Duration.ofHours(24));

	public static void main(String[] args) throws Exception {
		Options options = Options.parse(args);
		Files.createDirectories(options.output());
		Path corpusDirectory = options.output().resolve("corpus");
		BenchmarkCorpusGenerator generator = new BenchmarkCorpusGenerator();
		List<Run> runs = new ArrayList<>();
		for (int size : options.sizes()) {
			BenchmarkCorpusGenerator.Corpus corpus = generator.generate(corpusDirectory, size);
			for (int warmup = 0; warmup < options.warmups(); warmup++) {
				run(corpus, options, -warmup - 1);
			}
			for (int repetition = 1; repetition <= options.repetitions(); repetition++) {
				Run run = run(corpus, options, repetition);
				runs.add(run);
				System.out.println("M5 benchmark " + size + " repetition " + repetition + ": "
						+ run.status() + " in " + run.totalMillis() + " ms (" + run.scope() + ")");
			}
		}
		Map<String, Object> report = report(options, runs);
		Path json = options.output().resolve("benchmark-report.json");
		Files.writeString(json, GSON.toJson(report), UTF_8);
		writeCsv(options.output().resolve("benchmark-runs.csv"), runs);
		System.out.println("JSON report: " + json.toAbsolutePath());
	}

	private static Run run(BenchmarkCorpusGenerator.Corpus corpus, Options options, int repetition)
			throws Exception {
		long gcCount = gcCount();
		long gcMillis = gcMillis();
		long peakHeap = usedHeap();
		long peakRss = rssBytes();
		Map<String, Long> phases = new LinkedHashMap<>();
		long totalStarted = System.nanoTime();
		if (corpus.featureCount() <= options.fullUpTo()) {
			try {
			Path work = options.output().resolve("work-" + corpus.featureCount() + "-" + repetition);
			Files.createDirectories(work);
			DatasetService datasets = new DatasetService(work.resolve("datasets"), BENCHMARK_UPLOAD_LIMITS);
			ManagedDataset dataset;
			long started = System.nanoTime();
			try (var input = Files.newInputStream(corpus.geoJson())) {
				dataset = datasets.upload(corpus.geoJson().getFileName().toString(), "application/geo+json",
						Files.size(corpus.geoJson()), input, benchmarkImportOptions(options));
			}
			phases.put("parseInspect", System.nanoTime() - started);
			peakHeap = Math.max(peakHeap, usedHeap());
			peakRss = Math.max(peakRss, rssBytes());
			long storeBytes = Files.size(datasets.managedDirectory(dataset.id().toString())
					.resolve("normalized-features.v2w"));
			int workers = Math.max(1, Math.min(options.workers(), Runtime.getRuntime().availableProcessors()));
			try (GenerationJobService jobs = new GenerationJobService(work.resolve("jobs"), Duration.ofHours(1),
					workers, options.queueCapacity(), datasets, new TileOwnershipPlanner(),
					new Osm2WorldTileRenderer(new Osm2WorldEngineAdapter(new OsmTagMapper(), new BuildingRuleEngine())),
					new TilesetTreeAssembler(), new TilesetValidator())) {
				ManagedGenerationJob job = jobs.create(new GenerationJobSpec(dataset.id().toString(), HEIGHT,
						ModelingConfig.defaults().withLod(2), TilingConfig.defaults(workers, options.queueCapacity())));
				await(job, options.timeout());
				peakHeap = Math.max(peakHeap, usedHeap());
				peakRss = Math.max(peakRss, rssBytes());
				if (job.result() == null) return failed(corpus, repetition, "full", totalStarted, peakHeap,
						peakRss, gcCount, gcMillis, phases, job.error());
				JsonObject generation = JsonParser.parseString(Files.readString(
						jobs.resultFile(job.id().toString(), "generation-report.json"), UTF_8)).getAsJsonObject();
				JsonObject metrics = generation.getAsJsonObject("resourceMetrics");
				peakHeap = Math.max(peakHeap, metrics.get("peakHeapBytes").getAsLong());
				JsonObject phaseNanos = metrics.getAsJsonObject("phaseNanos");
				for (String name : phaseNanos.keySet()) phases.put(name, phaseNanos.get(name).getAsLong());
				return new Run(corpus.featureCount(), repetition, repetition == 1 ? "cold" : "hot", "full",
						"PASSED", millis(totalStarted), peakHeap, peakRss,
						Math.max(0, gcCount() - gcCount), Math.max(0, gcMillis() - gcMillis),
						generation.get("plannedTiles").getAsInt(), generation.get("triangleCount").getAsLong(),
						generation.get("outputBytes").getAsLong(), storeBytes, corpus.bytes(), corpus.sha256(),
						Map.copyOf(phases), null);
			}
			} catch (Exception exception) {
				return failed(corpus, repetition, "full", totalStarted, peakHeap,
						peakRss, gcCount, gcMillis, phases, exception.toString());
			}
		}

		try {
			Path work = options.output().resolve("work-" + corpus.featureCount() + "-" + repetition);
			Files.createDirectories(work);
			DatasetService datasets = new DatasetService(work.resolve("datasets"), BENCHMARK_UPLOAD_LIMITS);
			long started = System.nanoTime();
			ManagedDataset managed;
			try (var input = Files.newInputStream(corpus.geoJson())) {
				managed = datasets.upload(corpus.geoJson().getFileName().toString(), "application/geo+json",
						Files.size(corpus.geoJson()), input, benchmarkImportOptions(options));
			}
			var dataset = datasets.materialize(managed.id().toString(), HEIGHT);
			phases.put("parseInspectMaterialize", System.nanoTime() - started);
			peakHeap = Math.max(peakHeap, usedHeap());
			peakRss = Math.max(peakRss, rssBytes());
			long storeBytes = Files.size(datasets.managedDirectory(managed.id().toString())
					.resolve("normalized-features.v2w"));
			started = System.nanoTime();
			BuildingRuleEngine rules = new BuildingRuleEngine();
			ModelingConfig modeling = ModelingConfig.defaults().withLod(2);
			for (var building : dataset.buildings()) rules.evaluate(building, modeling);
			phases.put("rules", System.nanoTime() - started);
			peakHeap = Math.max(peakHeap, usedHeap());
			peakRss = Math.max(peakRss, rssBytes());
			started = System.nanoTime();
			var plan = new TileOwnershipPlanner().plan(dataset.buildings(), TilingConfig.DEFAULT_ZOOM, 4);
			phases.put("tiling", System.nanoTime() - started);
			peakHeap = Math.max(peakHeap, usedHeap());
			peakRss = Math.max(peakRss, rssBytes());
			return new Run(corpus.featureCount(), repetition, repetition == 1 ? "cold" : "hot", "analysis",
					"PASSED", millis(totalStarted), peakHeap, peakRss, Math.max(0, gcCount() - gcCount),
					Math.max(0, gcMillis() - gcMillis), plan.tiles().size(), 0, 0, storeBytes, corpus.bytes(),
					corpus.sha256(), Map.copyOf(phases), null);
		} catch (Exception exception) {
			return failed(corpus, repetition, "analysis", totalStarted, peakHeap,
					peakRss, gcCount, gcMillis, phases, exception.toString());
		}
	}

	private static Run failed(BenchmarkCorpusGenerator.Corpus corpus, int repetition, String scope,
			long started, long peakHeap, long peakRss, long gcCount, long gcMillis,
			Map<String, Long> phases, String error) {
		return new Run(corpus.featureCount(), repetition, repetition == 1 ? "cold" : "hot", scope,
				"FAILED", millis(started), peakHeap, peakRss, Math.max(0, gcCount() - gcCount),
				Math.max(0, gcMillis() - gcMillis), 0, 0, 0, 0, corpus.bytes(), corpus.sha256(),
				Map.copyOf(phases), error);
	}

	private static ImportOptions benchmarkImportOptions(Options options) {
		return new ImportOptions(null, null, null, options.timeout(), 0.05, 0.50);
	}

	private static Map<String, Object> report(Options options, List<Run> runs) {
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("schemaVersion", "1.0");
		report.put("generatedAt", Instant.now().toString());
		report.put("generatorVersion", BenchmarkCorpusGenerator.GENERATOR_VERSION);
		report.put("environment", environment());
		report.put("configuration", Map.of("sizes", options.sizes(), "warmups", options.warmups(),
				"repetitions", options.repetitions(), "fullUpTo", options.fullUpTo(),
				"workers", options.workers(), "queueCapacity", options.queueCapacity(),
				"timeoutSeconds", options.timeout().toSeconds()));
		report.put("runs", runs);
		List<Map<String, Object>> summaries = new ArrayList<>();
		for (int size : options.sizes()) {
			List<Run> successful = runs.stream().filter(run -> run.featureCount() == size)
					.filter(run -> "PASSED".equals(run.status())).toList();
			Map<String, Object> summary = new LinkedHashMap<>();
			summary.put("featureCount", size);
			summary.put("successfulRuns", successful.size());
			summary.put("failedRuns", options.repetitions() - successful.size());
			summary.put("scope", successful.isEmpty() ? "unknown" : successful.get(0).scope());
			summary.put("medianTotalMillis", median(successful.stream().map(Run::totalMillis).toList()));
			summary.put("coefficientOfVariation", coefficientOfVariation(
					successful.stream().map(Run::totalMillis).toList()));
			summary.put("medianPeakHeapBytes", median(successful.stream().map(Run::peakHeapBytes).toList()));
			summary.put("medianPeakRssBytes", median(successful.stream().map(Run::peakRssBytes).toList()));
			summary.put("medianOutputBytes", median(successful.stream().map(Run::outputBytes).toList()));
			summary.put("medianNormalizedStoreBytes", median(
					successful.stream().map(Run::normalizedStoreBytes).toList()));
			summary.put("medianTriangleCount", median(successful.stream().map(Run::triangleCount).toList()));
			summary.put("medianTileCount", median(successful.stream().map(run -> (long)run.tileCount()).toList()));
			summaries.add(summary);
		}
		report.put("summary", summaries);
		List<Map<String, Object>> cacheSummaries = new ArrayList<>();
		List<String> varianceWarnings = new ArrayList<>();
		for (int size : options.sizes()) {
			for (String cacheState : List.of("cold", "hot")) {
				List<Run> successful = runs.stream().filter(run -> run.featureCount() == size)
						.filter(run -> cacheState.equals(run.cacheState()))
						.filter(run -> "PASSED".equals(run.status())).toList();
				if (successful.isEmpty()) continue;
				double variation = coefficientOfVariation(successful.stream().map(Run::totalMillis).toList());
				cacheSummaries.add(Map.of("featureCount", size, "cacheState", cacheState,
						"successfulRuns", successful.size(), "medianTotalMillis",
						median(successful.stream().map(Run::totalMillis).toList()),
						"coefficientOfVariation", variation));
				if (successful.size() >= 2 && variation > 0.15) varianceWarnings.add(size + "/" + cacheState
						+ " coefficient of variation " + variation + " exceeds 0.15");
			}
		}
		report.put("cacheStateSummary", cacheSummaries);
		report.put("varianceWarnings", varianceWarnings);
		report.put("failedRunsExcludedFromStatistics", true);
		return report;
	}

	private static Map<String, Object> environment() {
		Map<String, Object> environment = new LinkedHashMap<>();
		environment.put("javaVersion", System.getProperty("java.version"));
		environment.put("jvm", System.getProperty("java.vm.name"));
		environment.put("jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
		environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
		environment.put("architecture", System.getProperty("os.arch"));
		environment.put("processors", Runtime.getRuntime().availableProcessors());
		environment.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
		environment.put("gitCommit", command("git", "rev-parse", "HEAD"));
		environment.put("gitDirty", !command("git", "status", "--porcelain").isBlank());
		return environment;
	}

	private static String command(String... command) {
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes(), UTF_8).trim();
			return process.waitFor() == 0 ? output : "unavailable";
		} catch (Exception exception) {
			return "unavailable";
		}
	}

	private static void writeCsv(Path file, List<Run> runs) throws IOException {
		try (BufferedWriter writer = Files.newBufferedWriter(file, UTF_8)) {
			writer.write("featureCount,repetition,cacheState,scope,status,totalMillis,peakHeapBytes,peakRssBytes,gcCollections,gcMillis,tileCount,triangleCount,outputBytes,normalizedStoreBytes,inputBytes,sha256,error\n");
			for (Run run : runs) {
				writer.write(String.join(",", Integer.toString(run.featureCount()), Integer.toString(run.repetition()),
						run.cacheState(), run.scope(), run.status(), Long.toString(run.totalMillis()),
						Long.toString(run.peakHeapBytes()), Long.toString(run.peakRssBytes()),
						Long.toString(run.gcCollections()),
						Long.toString(run.gcMillis()), Integer.toString(run.tileCount()),
						Long.toString(run.triangleCount()), Long.toString(run.outputBytes()),
						Long.toString(run.normalizedStoreBytes()), Long.toString(run.inputBytes()),
						run.sha256(), csv(run.error())));
				writer.newLine();
			}
		}
	}

	private static String csv(String value) {
		if (value == null) return "";
		return "\"" + value.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ') + "\"";
	}

	private static void await(ManagedGenerationJob job, Duration timeout) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (!job.state().terminal() && System.nanoTime() < deadline) Thread.sleep(50);
		if (!job.state().terminal()) {
			job.cancel();
			throw new IllegalStateException("Benchmark job exceeded " + timeout);
		}
		if (job.state() == GenerationJobState.CANCELLED || job.state() == GenerationJobState.FAILED) {
			throw new IllegalStateException("Benchmark job ended " + job.state() + ": " + job.error());
		}
	}

	private static long millis(long startedNanos) {
		return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
	}

	private static long usedHeap() {
		Runtime runtime = Runtime.getRuntime();
		return runtime.totalMemory() - runtime.freeMemory();
	}

	private static long rssBytes() {
		long pid = ProcessHandle.current().pid();
		try {
			String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
			if (os.contains("win")) {
				Process process = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
						"(Get-Process -Id " + pid + ").WorkingSet64").redirectErrorStream(true).start();
				String value = new String(process.getInputStream().readAllBytes(), UTF_8).trim();
				return process.waitFor() == 0 ? Long.parseLong(value) : 0;
			}
			Path status = Path.of("/proc/self/status");
			if (Files.isRegularFile(status)) {
				String line = Files.readAllLines(status, UTF_8).stream()
						.filter(value -> value.startsWith("VmRSS:")).findFirst().orElse("");
				String digits = line.replaceAll("[^0-9]", "");
				return digits.isEmpty() ? 0 : Long.parseLong(digits) * 1024;
			}
		} catch (Exception ignored) {
			// RSS is best-effort and remains zero when the host cannot expose it.
		}
		return 0;
	}

	private static long gcCount() {
		return ManagementFactory.getGarbageCollectorMXBeans().stream()
				.mapToLong(GarbageCollectorMXBean::getCollectionCount).filter(value -> value >= 0).sum();
	}

	private static long gcMillis() {
		return ManagementFactory.getGarbageCollectorMXBeans().stream()
				.mapToLong(GarbageCollectorMXBean::getCollectionTime).filter(value -> value >= 0).sum();
	}

	private static long median(List<Long> values) {
		if (values.isEmpty()) return 0;
		List<Long> sorted = values.stream().sorted().toList();
		return sorted.get(sorted.size() / 2);
	}

	private static double coefficientOfVariation(List<Long> values) {
		if (values.size() < 2) return 0;
		double mean = values.stream().mapToLong(Long::longValue).average().orElse(0);
		if (mean == 0) return 0;
		double variance = values.stream().mapToDouble(value -> Math.pow(value - mean, 2)).sum() / (values.size() - 1);
		return Math.sqrt(variance) / mean;
	}

	public record Run(int featureCount, int repetition, String cacheState, String scope, String status,
			long totalMillis, long peakHeapBytes, long peakRssBytes, long gcCollections, long gcMillis, int tileCount,
			long triangleCount, long outputBytes, long normalizedStoreBytes, long inputBytes, String sha256,
			Map<String, Long> phaseNanos, String error) {}

	record Options(Path output, List<Integer> sizes, int warmups, int repetitions, int fullUpTo,
			int workers, int queueCapacity, Duration timeout) {
		static Options parse(String[] args) {
			Path output = Path.of("output/m5-benchmark");
			List<Integer> sizes = List.of(1_000, 10_000, 100_000);
			int warmups = 1;
			int repetitions = 3;
			int fullUpTo = 0;
			int workers = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
			int queue = 128;
			Duration timeout = Duration.ofHours(3);
			for (String arg : args) {
				if (arg.startsWith("--output=")) output = Path.of(arg.substring(9));
				else if (arg.startsWith("--sizes=")) sizes = Arrays.stream(arg.substring(8).split(","))
						.map(Integer::parseInt).sorted(Comparator.naturalOrder()).toList();
				else if (arg.startsWith("--warmups=")) warmups = Integer.parseInt(arg.substring(10));
				else if (arg.startsWith("--repetitions=")) repetitions = Integer.parseInt(arg.substring(14));
				else if (arg.startsWith("--full-up-to=")) fullUpTo = Integer.parseInt(arg.substring(13));
				else if (arg.startsWith("--workers=")) workers = Integer.parseInt(arg.substring(10));
				else if (arg.startsWith("--queue=")) queue = Integer.parseInt(arg.substring(8));
				else if (arg.startsWith("--timeout-seconds=")) timeout = Duration.ofSeconds(Long.parseLong(arg.substring(18)));
				else throw new IllegalArgumentException("Unknown argument: " + arg);
			}
			if (sizes.isEmpty() || warmups < 0 || repetitions < 1 || workers < 1 || queue < 1) {
				throw new IllegalArgumentException("Invalid benchmark options");
			}
			return new Options(output, List.copyOf(sizes), warmups, repetitions, fullUpTo, workers, queue, timeout);
		}
	}
}
