package org.osm2world.buildingtiler.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import org.osm2world.buildingtiler.tiles.TileFailureCategory;
import org.osm2world.buildingtiler.tiles.TileOwnershipPlanner;
import org.osm2world.buildingtiler.tiles.TileRenderException;
import org.osm2world.buildingtiler.tiles.TileRenderer;
import org.osm2world.buildingtiler.tiles.TilesetTreeAssembler;
import org.osm2world.buildingtiler.tiles.TilesetValidator;

class Milestone5ResourcePolicyTest {

	@TempDir Path temporary;

	@Test
	void refusesEstimatedJobQuotaBeforeCreatingStagingAndWritesRedactedDiagnostics() throws Exception {
		JobResourcePolicy policy = policy(1, Duration.ofMinutes(1), Duration.ofSeconds(5), 8L * 1024 * 1024 * 1024);
		try (Fixture fixture = fixture(realRenderer(), policy)) {
			ManagedGenerationJob job = await(fixture.jobs().create(spec(fixture.datasetId())), 10_000);
			assertEquals(GenerationJobState.FAILED, job.state());
			assertTrue(job.error().contains("RESOURCE_JOB_QUOTA"));
			assertFalse(Files.exists(job.workDirectory().resolve("staging")));
			Path summary = job.workDirectory().resolve("diagnostics/summary.json");
			waitFor(() -> Files.isRegularFile(summary), 5_000);
			String diagnostics = Files.readString(summary);
			assertTrue(diagnostics.contains("RESOURCE_JOB_QUOTA"));
			assertFalse(diagnostics.contains(fixture.jobsRoot().toString()));
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			fixture.jobs().streamDiagnosticsZip(job.id().toString(), bytes);
			assertTrue(zipEntries(bytes).contains("diagnostics/summary.json"));
			assertTrue(zipEntries(bytes).contains("logs/events.ndjson"));
		}
	}

	@Test
	void tileTimeoutIsBoundedAndNeverPublishesRootResult() throws Exception {
		TileRenderer stuck = (work, lods, config, staging, cancelled) -> {
			try { Thread.sleep(30_000); }
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new CancellationException("interrupted by tile timeout");
			}
			throw new TileRenderException(TileFailureCategory.INTERNAL, "unexpected wakeup");
		};
		JobResourcePolicy policy = policy(8L * 1024 * 1024 * 1024, Duration.ofMillis(50),
				Duration.ofSeconds(5), 8L * 1024 * 1024 * 1024);
		try (Fixture fixture = fixture(stuck, policy)) {
			long started = System.nanoTime();
			ManagedGenerationJob job = await(fixture.jobs().create(spec(fixture.datasetId())), 5_000);
			assertEquals(GenerationJobState.FAILED, job.state());
			assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 3);
			assertFalse(Files.exists(job.workDirectory().resolve("result")));
		}
	}

	@Test
	void zipQuotaRejectsOversizedDownloadButDiagnosticsRemainAvailable() throws Exception {
		JobResourcePolicy policy = policy(8L * 1024 * 1024 * 1024, Duration.ofMinutes(1),
				Duration.ofSeconds(5), 1);
		try (Fixture fixture = fixture(realRenderer(), policy)) {
			ManagedGenerationJob job = await(fixture.jobs().create(spec(fixture.datasetId())), 10_000);
			assertTrue(job.result() != null);
			assertThrows(Exception.class,
					() -> fixture.jobs().streamZip(job.id().toString(), new ByteArrayOutputStream()));
			ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
			fixture.jobs().streamDiagnosticsZip(job.id().toString(), diagnostics);
			HashSet<String> entries = zipEntries(diagnostics);
			assertTrue(entries.contains("logs/tiles.ndjson"));
			assertTrue(entries.stream().anyMatch(value -> value.endsWith(".ndjson.1")));
		}
	}

	@Test
	void terminalFailedTileRecoveryUsesANewCleanStagingTree() throws Exception {
		AtomicBoolean injectOnce = new AtomicBoolean(true);
		TileRenderer delegate = realRenderer();
		TileRenderer renderer = (work, lods, config, staging, cancelled) -> {
			if (injectOnce.compareAndSet(true, false)) {
				throw new TileRenderException(TileFailureCategory.GEOMETRY, "one deterministic failed tile");
			}
			return delegate.render(work, lods, config, staging, cancelled);
		};
		try (Fixture fixture = fixture(renderer, policy(8L * 1024 * 1024 * 1024,
				Duration.ofMinutes(1), Duration.ofSeconds(5), 8L * 1024 * 1024 * 1024))) {
			ManagedGenerationJob original = await(fixture.jobs().create(spec(fixture.datasetId())), 10_000);
			assertEquals(GenerationJobState.COMPLETED_WITH_WARNINGS, original.state());
			assertEquals(1, original.result().failedTiles());
			ManagedGenerationJob recovery = await(
					fixture.jobs().retryFailed(original.id().toString()), 10_000);
			assertEquals(GenerationJobState.COMPLETED, recovery.state());
			assertFalse(original.id().equals(recovery.id()));
			assertFalse(original.workDirectory().equals(recovery.workDirectory()));
			assertEquals(0, recovery.result().failedTiles());
		}
	}

	@Test
	void consecutiveAndConcurrentJobsShareOnlyTheBoundedWorkerPool() throws Exception {
		try (Fixture fixture = fixture(realRenderer(), policy(8L * 1024 * 1024 * 1024,
				Duration.ofMinutes(1), Duration.ofSeconds(10), 8L * 1024 * 1024 * 1024))) {
			java.util.List<ManagedGenerationJob> jobs = new java.util.ArrayList<>();
			for (int index = 0; index < 8; index++) jobs.add(
					fixture.jobs().create(spec(fixture.datasetId())));
			for (ManagedGenerationJob job : jobs) {
				await(job, 20_000);
				assertTrue(job.result() != null);
				assertEquals(0, job.result().failedTiles());
			}
			assertTrue(fixture.jobs().largestWorkerPoolSize() <= 2);
			assertTrue(fixture.jobs().activeWorkerCount() <= 2);
		}
	}

	private Fixture fixture(TileRenderer renderer, JobResourcePolicy policy) throws Exception {
		Path datasetsRoot = temporary.resolve("datasets-" + java.util.UUID.randomUUID());
		DatasetService datasets = new DatasetService(datasetsRoot, UploadLimits.defaults());
		Path input = Path.of(getClass().getResource("/m0-polygons.geojson").toURI());
		ManagedDataset dataset;
		try (var stream = Files.newInputStream(input)) {
			dataset = datasets.upload("sample.geojson", "application/geo+json", Files.size(input), stream,
					ImportOptions.defaults());
		}
		Path jobsRoot = temporary.resolve("jobs-" + java.util.UUID.randomUUID());
		Files.createDirectories(jobsRoot);
		GenerationJobService jobs = new GenerationJobService(jobsRoot, Duration.ofHours(1), 2, 4, datasets,
				new TileOwnershipPlanner(), renderer, new TilesetTreeAssembler(), new TilesetValidator(), policy);
		return new Fixture(dataset.id().toString(), jobsRoot, jobs);
	}

	private static JobResourcePolicy policy(long maximumJobBytes, Duration tileTimeout,
			Duration jobTimeout, long maximumZipBytes) {
		return new JobResourcePolicy(8 * 1024, 0, maximumJobBytes, maximumZipBytes, 100_000,
				jobTimeout, tileTimeout, Duration.ofMillis(10), 1024,
				0, 1);
	}

	private static TileRenderer realRenderer() {
		return new Osm2WorldTileRenderer(new Osm2WorldEngineAdapter(new OsmTagMapper(), new BuildingRuleEngine()));
	}

	private static GenerationJobSpec spec(String datasetId) {
		return new GenerationJobSpec(datasetId,
				new HeightMapping("Elevation", HeightUnit.M, InvalidHeightPolicy.SKIP, 10_000),
				ModelingConfig.defaults().withLod(2), TilingConfig.defaults(2, 4));
	}

	private static ManagedGenerationJob await(ManagedGenerationJob job, long timeoutMillis) throws Exception {
		waitFor(() -> job.state().terminal(), timeoutMillis);
		return job;
	}

	private static void waitFor(java.util.function.BooleanSupplier condition, long timeoutMillis) throws Exception {
		long deadline = System.nanoTime() + Duration.ofMillis(timeoutMillis).toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for asynchronous work");
			Thread.sleep(10);
		}
	}

	private static HashSet<String> zipEntries(ByteArrayOutputStream bytes) throws Exception {
		HashSet<String> result = new HashSet<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) result.add(entry.getName());
		}
		return result;
	}

	private record Fixture(String datasetId, Path jobsRoot, GenerationJobService jobs) implements AutoCloseable {
		@Override public void close() { jobs.close(); }
	}
}
