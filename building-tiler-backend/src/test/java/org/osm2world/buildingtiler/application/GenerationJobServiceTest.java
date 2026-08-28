package org.osm2world.buildingtiler.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
	import java.io.ByteArrayInputStream;
	import java.io.ByteArrayOutputStream;
	import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
	import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.domain.HeightUnit;
import org.osm2world.buildingtiler.domain.InvalidHeightPolicy;
import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.domain.OutputFormat;
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

class GenerationJobServiceTest {

	@TempDir Path temporary;

	@Test
	void publishesValidatedAtomicResultAndReconciledReports() throws Exception {
		try (Fixture fixture = fixture(realRenderer(), Duration.ofHours(1))) {
			ManagedGenerationJob job = await(fixture.jobs().create(spec(fixture.datasetId())));
			assertEquals(GenerationJobState.COMPLETED, job.state());
			assertNotNull(job.result());
			assertTrue(job.result().validation().valid());
			assertEquals(job.result().successfulTiles(), job.result().validation().glbCount());
			assertEquals(job.result().plannedTiles(), job.result().successfulTiles());
			assertTrue(Files.isRegularFile(fixture.jobs().resultFile(job.id().toString(), "tileset.json")));
			assertTrue(Files.isRegularFile(fixture.jobs().resultFile(job.id().toString(), "manifest.json")));
			assertTrue(Files.isRegularFile(fixture.jobs().resultFile(job.id().toString(), "generation-report.json")));
			assertFalse(Files.exists(job.workDirectory().resolve("staging")));
			assertTrue(Files.isDirectory(job.workDirectory().resolve("result")));
			assertThrows(Exception.class,
					() -> fixture.jobs().resultFile(job.id().toString(), "../secret"));
			assertThrows(Exception.class,
					() -> fixture.jobs().resultAsset(job.id().toString(), "../tileset.json"));
			assertThrows(Exception.class,
					() -> fixture.jobs().resultAsset(job.id().toString(), "generation-report.json"));
		}
	}

	@Test
	void rejectsDeterministicTileFailureByDefaultWithoutPublishingAResult() throws Exception {
		AtomicBoolean failedOne = new AtomicBoolean();
		TileRenderer delegate = realRenderer();
		TileRenderer renderer = (work, lods, config, staging, cancelled) -> {
			if (failedOne.compareAndSet(false, true)) {
				throw new TileRenderException(TileFailureCategory.GEOMETRY, "injected deterministic geometry error");
			}
			return delegate.render(work, lods, config, staging, cancelled);
		};
		try (Fixture fixture = fixture(renderer, Duration.ofHours(1))) {
			ManagedGenerationJob job = await(fixture.jobs().create(spec(fixture.datasetId())));
			assertEquals(GenerationJobState.FAILED, job.state());
			assertEquals(null, job.result());
			assertTrue(job.error().contains("INCOMPLETE_RESULT"));
			assertFalse(Files.exists(job.workDirectory().resolve("result")));
		}
	}

	@Test
	void publishesClearlyMarkedPartialResultOnlyWhenExplicitThresholdsPermitIt() throws Exception {
		AtomicBoolean failedOne = new AtomicBoolean();
		TileRenderer delegate = realRenderer();
		TileRenderer renderer = (work, lods, config, staging, cancelled) -> {
			if (failedOne.compareAndSet(false, true)) {
				throw new TileRenderException(TileFailureCategory.GEOMETRY, "injected deterministic geometry error");
			}
			return delegate.render(work, lods, config, staging, cancelled);
		};
		try (Fixture fixture = fixture(renderer, Duration.ofHours(1))) {
			GenerationJobSpec partial = spec(fixture.datasetId(),
					DeliveryPolicy.allowPartial(1, 1, 100, 1));
			ManagedGenerationJob job = await(fixture.jobs().create(partial));
			assertEquals(GenerationJobState.COMPLETED_WITH_WARNINGS, job.state());
			assertTrue(job.result().incomplete());
			assertEquals(1, job.result().failedTiles());
			assertTrue(job.result().failedBuildings() > 0);
			assertTrue(job.result().successfulTiles() > 0);
			assertTrue(job.result().validation().valid());
			assertEquals(1, job.result().tileFailures().get(0).attempts());
			assertTrue(Files.isRegularFile(job.result().resultDirectory().resolve("INCOMPLETE-RESULT.txt")));
			String ledger = Files.readString(job.result().resultDirectory().resolve("modeling-ledger.json"));
			assertTrue(ledger.contains("FAILED_TILE"));
			for (String featureId : job.result().tileFailures().get(0).failedFeatureIds()) {
				assertTrue(ledger.contains(featureId), "Every missing building must have an attributed ledger entry");
			}
			var bytes = new ByteArrayOutputStream();
			fixture.jobs().streamZip(job.id().toString(), bytes);
			assertTrue(zipEntries(bytes).contains("INCOMPLETE-RESULT.txt"));
		}
	}

	@Test
	void rejectsPartialResultWhenExplicitThresholdIsExceeded() throws Exception {
		AtomicBoolean failedOne = new AtomicBoolean();
		TileRenderer delegate = realRenderer();
		TileRenderer renderer = (work, lods, config, staging, cancelled) -> {
			if (failedOne.compareAndSet(false, true)) {
				throw new TileRenderException(TileFailureCategory.GEOMETRY, "one failed tile");
			}
			return delegate.render(work, lods, config, staging, cancelled);
		};
		try (Fixture fixture = fixture(renderer, Duration.ofHours(1))) {
			ManagedGenerationJob job = await(fixture.jobs().create(spec(fixture.datasetId(),
					DeliveryPolicy.allowPartial(0, 1, 100, 1))));
			assertEquals(GenerationJobState.FAILED, job.state());
			assertTrue(job.error().contains("INCOMPLETE_RESULT_THRESHOLD_EXCEEDED"));
			assertEquals(null, job.result());
		}
	}

	@Test
	void retriesOnlyTransientFailureAndRecordsTheAttempt() throws Exception {
		AtomicBoolean first = new AtomicBoolean(true);
		TileRenderer delegate = realRenderer();
		TileRenderer renderer = (work, lods, config, staging, cancelled) -> {
			if (first.compareAndSet(true, false)) {
				throw new TileRenderException(TileFailureCategory.IO_TRANSIENT, "injected one-shot I/O error");
			}
			return delegate.render(work, lods, config, staging, cancelled);
		};
		try (Fixture fixture = fixture(renderer, Duration.ofHours(1))) {
			ManagedGenerationJob job = await(fixture.jobs().create(spec(fixture.datasetId())));
			assertEquals(GenerationJobState.COMPLETED_WITH_WARNINGS, job.state());
			assertEquals(0, job.result().failedTiles());
			assertTrue(job.result().warnings().stream().anyMatch(value -> value.contains("2 attempts")));
		}
	}

	@Test
	void allTileFailuresEndFailedWithoutPublishedResult() throws Exception {
		TileRenderer renderer = (work, lods, config, staging, cancelled) -> {
			throw new TileRenderException(TileFailureCategory.GEOMETRY, "always bad");
		};
		try (Fixture fixture = fixture(renderer, Duration.ofHours(1))) {
			ManagedGenerationJob job = await(fixture.jobs().create(spec(fixture.datasetId())));
			assertEquals(GenerationJobState.FAILED, job.state());
			assertEquals(null, job.result());
			assertFalse(Files.exists(job.workDirectory().resolve("result")));
		}
	}

	@Test
	void cancellationPropagatesAndWorkerConcurrencyNeverExceedsHardLimit() throws Exception {
		AtomicInteger active = new AtomicInteger();
		AtomicInteger maximum = new AtomicInteger();
		TileRenderer slow = (work, lods, config, staging, cancelled) -> {
			int current = active.incrementAndGet();
			maximum.accumulateAndGet(current, Math::max);
			try {
				for (int i = 0; i < 200; i++) {
					if (cancelled.getAsBoolean()) throw new CancellationException("cancelled");
					try { Thread.sleep(5); }
					catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						throw new CancellationException("interrupted");
					}
				}
				throw new TileRenderException(TileFailureCategory.INTERNAL, "test should cancel first");
			} finally {
				active.decrementAndGet();
			}
		};
		try (Fixture fixture = fixture(slow, Duration.ofHours(1))) {
			ManagedGenerationJob job = fixture.jobs().create(spec(fixture.datasetId()));
			waitFor(() -> job.state() == GenerationJobState.MODELING && active.get() > 0, 10_000);
			fixture.jobs().cancel(job.id().toString());
			waitFor(() -> job.state().terminal(), 10_000);
			assertEquals(GenerationJobState.CANCELLED, job.state());
			assertTrue(maximum.get() <= 2);
			assertTrue(fixture.jobs().largestWorkerPoolSize() <= 2);
		}
	}

	@Test
	void largeJobCannotFillTheGlobalQueueAheadOfASmallJob() throws Exception {
		AtomicInteger activeLargeTiles = new AtomicInteger();
		AtomicInteger smallStartsWhileLargeWasRunning = new AtomicInteger();
		TileRenderer delegate = realRenderer();
		TileRenderer renderer = (work, lods, config, staging, cancelled) -> {
			if (config.lod() == 2) {
				activeLargeTiles.incrementAndGet();
				try {
					try { Thread.sleep(1_500); }
					catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						throw new CancellationException("interrupted");
					}
					return delegate.render(work, lods, config, staging, cancelled);
				} finally {
					activeLargeTiles.decrementAndGet();
				}
			}
			if (activeLargeTiles.get() > 0) smallStartsWhileLargeWasRunning.incrementAndGet();
			return delegate.render(work, lods, config, staging, cancelled);
		};
		try (Fixture fixture = fixture(renderer, Duration.ofHours(1))) {
			ManagedGenerationJob large = fixture.jobs().create(spec(fixture.datasetId(), 2, 20, 1));
			waitFor(() -> activeLargeTiles.get() == 1, 10_000);
			ManagedGenerationJob firstSmall = fixture.jobs().create(spec(fixture.datasetId(), 3, 20, 1));
			ManagedGenerationJob secondSmall = fixture.jobs().create(spec(fixture.datasetId(), 3, 20, 1));
			waitFor(() -> smallStartsWhileLargeWasRunning.get() > 0, 750);
			await(firstSmall);
			await(secondSmall);
			await(large);
			assertTrue(smallStartsWhileLargeWasRunning.get() > 0,
					"The small job must enter the worker pool before the large job drains all of its tiles");
			assertNotNull(firstSmall.result());
			assertNotNull(secondSmall.result());
			assertNotNull(large.result());
		}
	}

	@Test
	void terminalAndOrphanDirectoriesAreRemovedAfterTtl() throws Exception {
		try (Fixture fixture = fixture(realRenderer(), Duration.ofMillis(1))) {
			ManagedGenerationJob job = await(fixture.jobs().create(spec(fixture.datasetId())));
			Path orphan = temporary.resolve("jobs/job-00000000-0000-0000-0000-000000000000");
			Files.createDirectory(orphan);
			Files.setLastModifiedTime(orphan, java.nio.file.attribute.FileTime.from(Instant.EPOCH));
			assertTrue(fixture.jobs().cleanupExpired(Instant.now().plusSeconds(2)) >= 2);
			assertFalse(Files.exists(job.workDirectory()));
			assertFalse(Files.exists(orphan));
		}
	}

	@Test
	void streamsOnlyThePublishedResultTreeAsPortableZipEntries() throws Exception {
		try (Fixture fixture = fixture(realRenderer(), Duration.ofHours(1))) {
			ManagedGenerationJob job = await(fixture.jobs().create(spec(fixture.datasetId())));
			var bytes = new ByteArrayOutputStream();
			fixture.jobs().streamZip(job.id().toString(), bytes);
			var entries = new HashSet<String>();
			try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
				for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
					assertFalse(entry.getName().contains("\\"));
					assertFalse(entry.getName().contains(".."));
					entries.add(entry.getName());
				}
			}
			assertTrue(entries.contains("tileset.json"));
			assertTrue(entries.contains("manifest.json"));
			assertTrue(entries.contains("generation-report.json"));
			assertTrue(entries.contains("modeling-ledger.json"));
			assertFalse(entries.stream().anyMatch(value -> value.startsWith("logs/")
					|| value.startsWith("diagnostics/") || value.startsWith("staging/")));
		}
	}

	private Fixture fixture(TileRenderer renderer, Duration ttl) throws Exception {
		Path datasetsRoot = temporary.resolve("datasets-" + java.util.UUID.randomUUID());
		DatasetService datasets = new DatasetService(datasetsRoot, UploadLimits.defaults());
		Path input = Path.of(getClass().getResource("/m0-polygons.geojson").toURI());
		ManagedDataset dataset;
		try (var stream = Files.newInputStream(input)) {
			dataset = datasets.upload("sample.geojson", "application/geo+json", Files.size(input), stream,
					ImportOptions.defaults());
		}
		Path jobsRoot = temporary.resolve("jobs");
		Files.createDirectories(jobsRoot);
		GenerationJobService jobs = new GenerationJobService(jobsRoot, ttl, 2, 4, datasets,
				new TileOwnershipPlanner(), renderer, new TilesetTreeAssembler(), new TilesetValidator());
		return new Fixture(dataset.id().toString(), jobs);
	}

	private static TileRenderer realRenderer() {
		return new Osm2WorldTileRenderer(new Osm2WorldEngineAdapter(new OsmTagMapper(), new BuildingRuleEngine()));
	}

	private static GenerationJobSpec spec(String datasetId) {
		return spec(datasetId, DeliveryPolicy.requireComplete());
	}

	private static GenerationJobSpec spec(String datasetId, DeliveryPolicy deliveryPolicy) {
		return new GenerationJobSpec(datasetId,
				new HeightMapping("Elevation", HeightUnit.M, InvalidHeightPolicy.SKIP, 10_000),
				ModelingConfig.defaults().withLod(2), TilingConfig.defaults(2, 4), deliveryPolicy);
	}

	private static GenerationJobSpec spec(String datasetId, int lod, int zoom) {
		return spec(datasetId, lod, zoom, 2);
	}

	private static GenerationJobSpec spec(String datasetId, int lod, int zoom, int workers) {
		TilingConfig tiling = new TilingConfig(zoom, List.of(lod), workers, 4, 1, 0, 4,
				List.of(OutputFormat.THREE_D_TILES));
		return new GenerationJobSpec(datasetId,
				new HeightMapping("Elevation", HeightUnit.M, InvalidHeightPolicy.SKIP, 10_000),
				ModelingConfig.defaults().withLod(lod), tiling);
	}

	private static HashSet<String> zipEntries(ByteArrayOutputStream bytes) throws Exception {
		var entries = new HashSet<String>();
		try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
				entries.add(entry.getName());
			}
		}
		return entries;
	}

	private static ManagedGenerationJob await(ManagedGenerationJob job) throws Exception {
		waitFor(() -> job.state().terminal(), 30_000);
		return job;
	}

	private static void waitFor(java.util.function.BooleanSupplier condition, long timeoutMillis) throws Exception {
		long deadline = System.nanoTime() + Duration.ofMillis(timeoutMillis).toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for asynchronous job");
			Thread.sleep(10);
		}
	}

	private record Fixture(String datasetId, GenerationJobService jobs) implements AutoCloseable {
		@Override public void close() { jobs.close(); }
	}
}
