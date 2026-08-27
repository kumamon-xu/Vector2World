package org.osm2world.buildingtiler.application;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.osm2world.buildingtiler.gis.DatasetErrorCode;
import org.osm2world.buildingtiler.gis.DatasetImportException;
import org.osm2world.buildingtiler.gis.DatasetReadResult;
import org.osm2world.buildingtiler.tiles.TileOwnershipPlanner;
import org.osm2world.buildingtiler.tiles.TileRenderException;
import org.osm2world.buildingtiler.tiles.TileRenderResult;
import org.osm2world.buildingtiler.tiles.TileRenderer;
import org.osm2world.buildingtiler.tiles.TileWork;
import org.osm2world.buildingtiler.tiles.TilesetTreeAssembler;
import org.osm2world.buildingtiler.tiles.TilesetValidator;

import com.google.gson.Gson;

public final class GenerationJobService implements AutoCloseable {

	private static final Gson GSON = new Gson();
	private final Path storageRoot;
	private final Duration ttl;
	private final int hardWorkerLimit;
	private final int queueCapacity;
	private final DatasetService datasets;
	private final TileOwnershipPlanner planner;
	private final TileRenderer renderer;
	private final TilesetTreeAssembler treeAssembler;
	private final TilesetValidator validator;
	private final GenerationResultWriter resultWriter;
	private final JobResourcePolicy resourcePolicy;
	private final ThreadPoolExecutor workers;
	private final ExecutorService coordinators;
	private final ScheduledExecutorService heartbeats;
	private final ScheduledExecutorService timeouts;
	private final Map<UUID, ManagedGenerationJob> jobs = new ConcurrentHashMap<>();
	private final Map<UUID, CappedJsonLog> eventLogs = new ConcurrentHashMap<>();
	private final Map<UUID, CappedJsonLog> tileLogs = new ConcurrentHashMap<>();
	private final Map<UUID, ScheduledFuture<?>> jobTimeouts = new ConcurrentHashMap<>();

	public GenerationJobService(Path storageRoot, Duration ttl, int hardWorkerLimit, int queueCapacity,
			DatasetService datasets, TileOwnershipPlanner planner, TileRenderer renderer,
			TilesetTreeAssembler treeAssembler, TilesetValidator validator) throws IOException {
		this(storageRoot, ttl, hardWorkerLimit, queueCapacity, datasets, planner, renderer,
				treeAssembler, validator, JobResourcePolicy.defaults());
	}

	public GenerationJobService(Path storageRoot, Duration ttl, int hardWorkerLimit, int queueCapacity,
			DatasetService datasets, TileOwnershipPlanner planner, TileRenderer renderer,
			TilesetTreeAssembler treeAssembler, TilesetValidator validator,
			JobResourcePolicy resourcePolicy) throws IOException {
		this.storageRoot = storageRoot.toAbsolutePath().normalize();
		this.ttl = ttl == null ? Duration.ofHours(24) : ttl;
		this.hardWorkerLimit = hardWorkerLimit > 0 ? hardWorkerLimit
				: Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
		this.queueCapacity = queueCapacity > 0 ? queueCapacity : 128;
		this.datasets = datasets;
		this.planner = planner;
		this.renderer = renderer;
		this.treeAssembler = treeAssembler;
		this.validator = validator;
		this.resourcePolicy = resourcePolicy == null ? JobResourcePolicy.defaults() : resourcePolicy;
		this.resultWriter = new GenerationResultWriter();
		Files.createDirectories(this.storageRoot);
		ThreadFactory workerFactory = namedFactory("vector2world-tile-");
		this.workers = new ThreadPoolExecutor(this.hardWorkerLimit, this.hardWorkerLimit,
				0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(this.queueCapacity), workerFactory,
				(execution, executor) -> {
					try {
						if (executor.isShutdown()) throw new RejectedExecutionException("Tile worker is shutting down");
						executor.getQueue().put(execution);
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						throw new RejectedExecutionException("Interrupted while applying tile-worker backpressure", exception);
					}
				});
		this.coordinators = Executors.newFixedThreadPool(Math.max(1, Math.min(4, this.hardWorkerLimit)),
				namedFactory("vector2world-job-"));
		this.heartbeats = Executors.newSingleThreadScheduledExecutor(namedFactory("vector2world-heartbeat-"));
		this.timeouts = Executors.newSingleThreadScheduledExecutor(namedFactory("vector2world-timeout-"));
		this.heartbeats.scheduleAtFixedRate(() -> jobs.values().forEach(ManagedGenerationJob::heartbeat),
				15, 15, TimeUnit.SECONDS);
	}

	public ManagedGenerationJob create(GenerationJobSpec spec) throws IOException {
		if (spec.tilingConfig().workerCount() > recommendedWorkerCount()) {
			throw new DatasetImportException(DatasetErrorCode.GENERATION_JOB_REJECTED,
					"Requested workerCount exceeds the adaptive limit " + recommendedWorkerCount()
							+ " (hard limit " + hardWorkerLimit + ")");
		}
		if (spec.tilingConfig().queueCapacity() > queueCapacity) {
			throw new DatasetImportException(DatasetErrorCode.GENERATION_JOB_REJECTED,
					"Requested queueCapacity exceeds the configured hard limit " + queueCapacity);
		}
		// Resolve the dataset before accepting work so a missing ID cannot create a false job.
		datasets.get(spec.datasetId());
		UUID id = UUID.randomUUID();
		Path directory = safeDirectory(id);
		Files.createDirectory(directory);
		Files.createDirectory(directory.resolve("logs"));
		Files.createDirectory(directory.resolve("diagnostics"));
		Instant created = Instant.now();
		ManagedGenerationJob job = new ManagedGenerationJob(id, spec, directory, created, created.plus(ttl),
				created.plus(resourcePolicy.jobTimeout()));
		CappedJsonLog eventLog = new CappedJsonLog(directory.resolve("logs/events.ndjson"),
				resourcePolicy.maximumLogBytes());
		eventLogs.put(id, eventLog);
		tileLogs.put(id, new CappedJsonLog(directory.resolve("logs/tiles.ndjson"),
				resourcePolicy.maximumLogBytes()));
		job.subscribe(event -> appendEvent(job.id(), event));
		for (GenerationJobEvent event : job.eventsAfter(0)) appendEvent(job.id(), event);
		jobs.put(id, job);
		Future<?> coordinator = coordinators.submit(() -> run(job));
		job.track(coordinator);
		ScheduledFuture<?> timeout = timeouts.schedule(() -> {
			if (job.timeout("RESOURCE_JOB_TIMEOUT: exceeded " + resourcePolicy.jobTimeout())) {
				writeDiagnostics(job, new JobMetrics());
			}
		}, resourcePolicy.jobTimeout().toMillis(), TimeUnit.MILLISECONDS);
		jobTimeouts.put(id, timeout);
		if (job.state().terminal() && jobTimeouts.remove(id, timeout)) timeout.cancel(false);
		return job;
	}

	public ManagedGenerationJob get(String id) throws DatasetImportException {
		UUID uuid = parseId(id);
		ManagedGenerationJob job = jobs.get(uuid);
		if (job == null) throw notFound(id);
		return job;
	}

	public Path managedDirectory(String id) throws DatasetImportException {
		ManagedGenerationJob job = get(id);
		if (job.result() != null && Files.isDirectory(job.result().resultDirectory())) {
			return job.result().resultDirectory();
		}
		return job.workDirectory();
	}

	public Path storageRoot() { return storageRoot; }

	public ManagedGenerationJob cancel(String id) throws DatasetImportException {
		ManagedGenerationJob job = get(id);
		if (job.cancel()) writeDiagnostics(job, new JobMetrics());
		return job;
	}

	/**
	 * Starts a clean recovery run from the immutable source/configuration. In-job
	 * transient retries target only the failed tile; terminal recovery uses a new
	 * staging tree so artifacts from different fingerprints can never be mixed.
	 */
	public ManagedGenerationJob retryFailed(String id) throws IOException {
		ManagedGenerationJob source = get(id);
		boolean recoverable = source.state() == GenerationJobState.FAILED
				|| (source.result() != null && source.result().failedTiles() > 0);
		if (!source.state().terminal() || !recoverable) {
			throw new DatasetImportException(DatasetErrorCode.GENERATION_JOB_REJECTED,
					"Only a terminal job with failed tiles can be recovered");
		}
		return create(source.spec());
	}

	public Path resultFile(String id, String name) throws DatasetImportException {
		ManagedGenerationJob job = completed(id);
		String relative = switch (name) {
			case "tileset.json" -> "tileset.json";
			case "manifest.json" -> "manifest.json";
			case "generation-report.json" -> "generation-report.json";
			default -> throw new DatasetImportException(DatasetErrorCode.INVALID_REQUEST,
					"Unsupported job result file: " + name);
		};
		Path root = job.result().resultDirectory().toAbsolutePath().normalize();
		Path file = root.resolve(relative).normalize();
		if (!file.startsWith(root) || !Files.isRegularFile(file)) {
			throw new DatasetImportException(DatasetErrorCode.GENERATION_JOB_NOT_READY,
					"Job result file is unavailable: " + name);
		}
		return file;
	}

	public Path resultAsset(String id, String relativePath) throws DatasetImportException {
		ManagedGenerationJob job = completed(id);
		if (relativePath == null || relativePath.isBlank() || relativePath.contains("\\")) {
			throw new DatasetImportException(DatasetErrorCode.INVALID_REQUEST,
					"Result asset path must be a non-empty portable path");
		}
		String portable = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
		if (!(portable.equals("tileset.json") || portable.endsWith(".tileset.json")
				|| portable.endsWith(".glb"))) {
			throw new DatasetImportException(DatasetErrorCode.INVALID_REQUEST,
					"Only tileset JSON and GLB result assets are public");
		}
		Path root = job.result().resultDirectory().toAbsolutePath().normalize();
		Path file = root.resolve(portable).normalize();
		if (!file.startsWith(root) || !Files.isRegularFile(file)) {
			throw new DatasetImportException(DatasetErrorCode.GENERATION_JOB_NOT_READY,
					"Generation result asset is unavailable: " + relativePath);
		}
		return file;
	}

	public void streamZip(String id, OutputStream target) throws IOException {
		ManagedGenerationJob job = completed(id);
		if (job.result().outputBytes() > resourcePolicy.maximumZipBytes()) {
			throw new DatasetImportException(DatasetErrorCode.GENERATION_JOB_REJECTED,
					"RESOURCE_ZIP_QUOTA: result exceeds the configured ZIP limit of "
							+ resourcePolicy.maximumZipBytes() + " bytes");
		}
		job.acquireResult();
		try (ZipOutputStream zip = new ZipOutputStream(target, UTF_8)) {
			Path root = job.result().resultDirectory().toAbsolutePath().normalize();
			try (var files = Files.walk(root)) {
				for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
					String entryName = root.relativize(file).toString().replace('\\', '/');
					zip.putNextEntry(new ZipEntry(entryName));
					Files.copy(file, zip);
					zip.closeEntry();
				}
			}
		} finally {
			job.releaseResult();
		}
	}

	public void streamDiagnosticsZip(String id, OutputStream target) throws IOException {
		ManagedGenerationJob job = get(id);
		if (!job.state().terminal()) {
			throw new DatasetImportException(DatasetErrorCode.GENERATION_JOB_NOT_READY,
					"Diagnostics are available after the job reaches a terminal state");
		}
		Path summary = job.workDirectory().resolve("diagnostics/summary.json");
		for (int attempt = 0; attempt < 20 && !Files.isRegularFile(summary); attempt++) {
			try { Thread.sleep(25); }
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for terminal diagnostics", exception);
			}
		}
		job.acquireResult();
		try (ZipOutputStream zip = new ZipOutputStream(target, UTF_8)) {
			for (String directoryName : List.of("logs", "diagnostics")) {
				Path directory = job.workDirectory().resolve(directoryName);
				if (!Files.isDirectory(directory)) continue;
				try (var files = Files.list(directory)) {
					for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
						zip.putNextEntry(new ZipEntry(directoryName + "/" + file.getFileName()));
						Files.copy(file, zip);
						zip.closeEntry();
					}
				}
			}
		} finally {
			job.releaseResult();
		}
	}

	public int cleanupExpired(Instant now) {
		int removed = 0;
		for (ManagedGenerationJob job : jobs.values()) {
			if (job.state().terminal() && !job.hasActiveReaders() && job.expiresAt().isBefore(now)
					&& jobs.remove(job.id(), job)) {
				eventLogs.remove(job.id());
				tileLogs.remove(job.id());
				ScheduledFuture<?> timeout = jobTimeouts.remove(job.id());
				if (timeout != null) timeout.cancel(false);
				try { deleteTree(job.workDirectory()); removed++; }
				catch (IOException ignored) { /* Windows locks are retried by the next orphan pass. */ }
			}
		}
		if (Files.isDirectory(storageRoot)) {
			try (var directories = Files.list(storageRoot)) {
				for (Path directory : directories.filter(Files::isDirectory)
						.filter(path -> path.getFileName().toString().startsWith("job-")).toList()) {
					boolean live = jobs.values().stream().anyMatch(job -> job.workDirectory().equals(directory));
					if (!live && Files.getLastModifiedTime(directory).toInstant().plus(ttl).isBefore(now)) {
						try { deleteTree(directory); removed++; }
						catch (IOException ignored) { /* retry later */ }
					}
				}
			} catch (IOException ignored) { /* storage may be temporarily unavailable */ }
		}
		return removed;
	}

	public int hardWorkerLimit() { return hardWorkerLimit; }
	public int recommendedWorkerCount() {
		long available = Math.max(0, Runtime.getRuntime().maxMemory() - resourcePolicy.reservedHeapBytes());
		long byHeap = Math.max(1, available / resourcePolicy.estimatedWorkerHeapBytes());
		return (int)Math.max(1, Math.min(hardWorkerLimit, byHeap));
	}
	public int queueCapacity() { return queueCapacity; }
	public int largestWorkerPoolSize() { return workers.getLargestPoolSize(); }
	public int activeWorkerCount() { return workers.getActiveCount(); }

	private void run(ManagedGenerationJob job) {
		Path staging = job.workDirectory().resolve("staging");
		Instant started = Instant.now();
		JobMetrics metrics = new JobMetrics();
		try {
			job.transition(GenerationJobState.VALIDATING, "Validating dataset and configuration");
			long phase = metrics.start();
			DatasetReadResult dataset = datasets.materialize(job.spec().datasetId(), job.spec().heightMapping());
			metrics.finish("parseAndRepair", phase);
			ensureCapacity(dataset.metadata().validBuildings());
			checkCancellation(job);
			job.transition(GenerationJobState.PREPARING, "Preparing isolated staging directory");
			Files.createDirectory(staging);
			job.transition(GenerationJobState.TILING, "Assigning centroid owners at Z"
					+ job.spec().tilingConfig().zoom());
			phase = metrics.start();
			var plan = planner.plan(dataset.buildings(), job.spec().tilingConfig().zoom(),
					job.spec().tilingConfig().largeBuildingTileSpanWarning());
			metrics.finish("tiling", phase);
			for (TileWork tile : plan.tiles()) {
				if (tile.features().size() > resourcePolicy.maximumFeaturesPerTile()) {
					throw new IOException("RESOURCE_TILE_FEATURE_LIMIT: " + tile.tile() + " has "
							+ tile.features().size() + " features; limit is "
							+ resourcePolicy.maximumFeaturesPerTile());
				}
			}
			job.progress(0, plan.tiles().size(), "Tiling plan contains " + plan.tiles().size() + " owner tiles");
			checkCancellation(job);
			job.transition(GenerationJobState.MODELING, "Rendering tiles with bounded workers");
			List<String> warnings = new ArrayList<>(plan.warnings());
			phase = metrics.start();
			TileBatch batch = executeTiles(job, staging, plan.tiles(), warnings, metrics);
			metrics.finish("modelingAndGlb", phase);
			checkCancellation(job);
			if (batch.successes().isEmpty()) {
				throw new IOException("All " + plan.tiles().size() + " tiles failed; no result was published");
			}
			if (!batch.failures().isEmpty()) {
				warnings.add(batch.failures().size() + " tile(s) failed and were omitted from the tree");
			}
			for (TileRenderResult result : batch.successes()) {
				warnings.addAll(result.warnings());
				if (!result.featureFailures().isEmpty()) warnings.add(result.tile() + " isolated "
						+ result.featureFailures().size() + " feature failure(s)");
			}
			job.transition(GenerationJobState.BUILDING_TILESET, "Building sparse external tileset tree");
			phase = metrics.start();
			List<String> successfulTiles = batch.successes().stream().map(TileRenderResult::tile).toList();
			treeAssembler.assembleTileIds(staging, successfulTiles, job.spec().tilingConfig().lods());
			metrics.finish("tilesetTree", phase);
			job.transition(GenerationJobState.VALIDATING_RESULT, "Validating recursive 3D Tiles result");
			phase = metrics.start();
			int expectedContents = batch.successes().size() * job.spec().tilingConfig().lods().size();
			TilesetValidator.ValidationResult preliminary = validator.validate(staging, expectedContents);
			if (!preliminary.valid()) throw new IOException("Result validation failed: " + preliminary.errors());
			metrics.finish("validation", phase);
			Instant finished = Instant.now();
			var write = resultWriter.write(staging, job, dataset, plan, batch.successes(), batch.failures(),
					List.copyOf(warnings), preliminary, started, finished,
					metrics.snapshot(job.spec().tilingConfig().workerCount()));
			TilesetValidator.ValidationResult finalValidation = validator.validate(staging, expectedContents);
			if (!finalValidation.valid()) throw new IOException("Manifest/report reconciliation failed: "
					+ finalValidation.errors());
			Path resultDirectory = job.workDirectory().resolve("result");
			publish(staging, resultDirectory);
			int modeled = batch.successes().stream().mapToInt(TileRenderResult::modeledBuildings).sum();
			int meshes = batch.successes().stream().mapToInt(TileRenderResult::meshCount).sum();
			long vertices = batch.successes().stream().mapToLong(TileRenderResult::vertexCount).sum();
			long triangles = batch.successes().stream().mapToLong(TileRenderResult::triangleCount).sum();
			job.complete(new GenerationJobResult(resultDirectory, plan.tiles().size(), batch.successes().size(),
					batch.failures().size(), modeled, meshes, vertices, triangles, write.outputBytes(),
					plan.ownershipHash(), successfulTiles,
					batch.failures(), warnings, write.artifacts(), finalValidation));
		} catch (CancellationException exception) {
			if (!job.state().terminal()) job.cancel();
			preserveStaging(job, staging);
		} catch (Exception exception) {
			if (job.cancellationRequested()) job.cancel();
			else job.fail(exception.getClass().getSimpleName() + ": " + exception.getMessage());
			preserveStaging(job, staging);
		} finally {
			writeDiagnostics(job, metrics);
		}
	}

	private TileBatch executeTiles(ManagedGenerationJob job, Path staging, List<TileWork> work,
			List<String> warnings, JobMetrics metrics) throws InterruptedException, IOException {
		CompletionService<TileExecution> completion = new ExecutorCompletionService<>(workers);
		Semaphore jobLimit = new Semaphore(job.spec().tilingConfig().workerCount());
		List<Future<TileExecution>> submitted = new ArrayList<>();
		Map<Future<TileExecution>, TileWork> workByFuture = new ConcurrentHashMap<>();
		Map<Future<TileExecution>, ScheduledFuture<?>> timeoutByFuture = new ConcurrentHashMap<>();
		Set<Future<TileExecution>> timedOut = ConcurrentHashMap.newKeySet();
		for (TileWork tile : work) {
			checkCancellation(job);
			Future<TileExecution> future = completion.submit(() -> executeTile(job, staging, tile, jobLimit, metrics));
			submitted.add(future);
			workByFuture.put(future, tile);
			ScheduledFuture<?> timeout = timeouts.schedule(() -> {
				if (!future.isDone()) {
					timedOut.add(future);
					future.cancel(true);
				}
			}, resourcePolicy.tileTimeout().toMillis(), TimeUnit.MILLISECONDS);
			timeoutByFuture.put(future, timeout);
			job.track(future);
		}
		List<TileRenderResult> successes = new ArrayList<>();
		List<TileFailure> failures = new ArrayList<>();
		int processed = 0;
		try {
		for (processed = 1; processed <= submitted.size(); processed++) {
			checkCancellation(job);
			Future<TileExecution> future = completion.take();
			ScheduledFuture<?> timeout = timeoutByFuture.remove(future);
			if (timeout != null) timeout.cancel(false);
			job.untrack(future);
			try {
				TileExecution execution = future.get();
				if (execution.result() != null) {
					successes.add(execution.result());
					metrics.emitted(execution.result().outputBytes());
					if (metrics.emittedBytes() > resourcePolicy.maximumJobBytes()) {
						throw new IOException("RESOURCE_JOB_QUOTA: emitted tile bytes exceed "
								+ resourcePolicy.maximumJobBytes());
					}
					if (processed % 16 == 0 || processed == submitted.size()) ensureDiskReserve();
					if (execution.attempts() > 1) warnings.add(execution.result().tile()
							+ " succeeded after " + execution.attempts() + " attempts");
				} else failures.add(execution.failure());
			} catch (CancellationException exception) {
				if (job.cancellationRequested()) throw exception;
				TileWork tile = workByFuture.get(future);
				if (timedOut.remove(future)) failures.add(new TileFailure(tile.tile().toString(),
						"TIMEOUT", 1, true, "Tile exceeded " + resourcePolicy.tileTimeout()));
				else failures.add(new TileFailure(tile.tile().toString(), "CANCELLED", 1, true,
						"Tile worker was interrupted"));
			} catch (ExecutionException exception) {
				Throwable cause = exception.getCause();
				if (cause instanceof CancellationException) throw new CancellationException(cause.getMessage());
				failures.add(new TileFailure("unknown", "INTERNAL", 1, false, cause.toString()));
			}
			job.progress(processed, submitted.size(), "Processed " + processed + "/" + submitted.size() + " tiles");
		}
		return new TileBatch(List.copyOf(successes), List.copyOf(failures));
		} finally {
			for (ScheduledFuture<?> timeout : timeoutByFuture.values()) timeout.cancel(false);
			if (processed <= submitted.size()) {
				for (Future<TileExecution> future : submitted) {
					if (!future.isDone()) future.cancel(true);
					job.untrack(future);
				}
			}
		}
	}

	private TileExecution executeTile(ManagedGenerationJob job, Path staging, TileWork tile, Semaphore jobLimit,
			JobMetrics metrics)
			throws InterruptedException {
		jobLimit.acquire();
		try {
			int attempts = 0;
			while (true) {
				attempts++;
				checkCancellation(job);
				try {
					logTileAttempt(job, tile, attempts, "STARTED", null, null);
					TileRenderResult result = renderer.render(tile, job.spec().tilingConfig().lods(),
							job.spec().modelingConfig(), staging, job::cancellationRequested);
					logTileAttempt(job, tile, attempts, "SUCCEEDED", null, null);
					return new TileExecution(result, null, attempts);
				} catch (TileRenderException exception) {
					logTileAttempt(job, tile, attempts, "FAILED", exception.category().name(), exception.getMessage());
					if (exception.retryable() && attempts <= job.spec().tilingConfig().transientRetryCount()) {
						metrics.retried();
						long multiplier = 1L << Math.min(20, attempts - 1);
						long delay = Math.min(30_000, resourcePolicy.retryBaseDelay().toMillis() * multiplier);
						Thread.sleep(delay);
						continue;
					}
					return new TileExecution(null, new TileFailure(tile.tile().toString(),
							exception.category().name(), attempts, exception.retryable(), exception.getMessage()), attempts);
				}
			}
		} finally {
			jobLimit.release();
		}
	}

	private ManagedGenerationJob completed(String id) throws DatasetImportException {
		ManagedGenerationJob job = get(id);
		if (job.result() == null || (job.state() != GenerationJobState.COMPLETED
				&& job.state() != GenerationJobState.COMPLETED_WITH_WARNINGS)) {
			throw new DatasetImportException(DatasetErrorCode.GENERATION_JOB_NOT_READY,
					"Generation job has no published result: " + id);
		}
		return job;
	}

	private Path safeDirectory(UUID id) {
		Path result = storageRoot.resolve("job-" + id).normalize();
		if (!result.startsWith(storageRoot)) throw new IllegalStateException("Job path escaped storage root");
		return result;
	}

	private static UUID parseId(String id) throws DatasetImportException {
		try { return UUID.fromString(id); }
		catch (RuntimeException exception) { throw notFound(id); }
	}

	private static DatasetImportException notFound(String id) {
		return new DatasetImportException(DatasetErrorCode.GENERATION_JOB_NOT_FOUND,
				"Generation job does not exist: " + id);
	}

	private static void checkCancellation(ManagedGenerationJob job) {
		if (job.cancellationRequested() || Thread.currentThread().isInterrupted()) {
			throw new CancellationException("Generation cancelled");
		}
	}

	private void ensureCapacity(long buildings) throws IOException {
		long estimate;
		try {
			estimate = Math.addExact(64L * 1024 * 1024,
					Math.multiplyExact(buildings, resourcePolicy.estimatedBytesPerBuilding()));
		} catch (ArithmeticException exception) {
			throw new IOException("RESOURCE_ESTIMATE_OVERFLOW: building count is too large", exception);
		}
		if (estimate > resourcePolicy.maximumJobBytes()) {
			throw new IOException("RESOURCE_JOB_QUOTA: estimated " + estimate
					+ " bytes exceeds job quota " + resourcePolicy.maximumJobBytes());
		}
		long usable = Files.getFileStore(storageRoot).getUsableSpace();
		if (usable < resourcePolicy.minimumUsableDiskBytes()
				|| usable - resourcePolicy.minimumUsableDiskBytes() < estimate) {
			throw new IOException("RESOURCE_DISK_SPACE: usable " + usable + " bytes; requires "
					+ estimate + " plus reserve " + resourcePolicy.minimumUsableDiskBytes());
		}
	}

	private void ensureDiskReserve() throws IOException {
		long usable = Files.getFileStore(storageRoot).getUsableSpace();
		if (usable < resourcePolicy.minimumUsableDiskBytes()) {
			throw new IOException("RESOURCE_DISK_SPACE: usable " + usable
					+ " bytes is below reserve " + resourcePolicy.minimumUsableDiskBytes());
		}
	}

	private static void publish(Path staging, Path result) throws IOException, InterruptedException {
		IOException failure = null;
		for (int attempt = 0; attempt < 4; attempt++) {
			try {
				try { Files.move(staging, result, ATOMIC_MOVE); }
				catch (IOException atomicFailure) { Files.move(staging, result); }
				return;
			} catch (IOException exception) {
				failure = exception;
				Thread.sleep(50L * (attempt + 1));
			}
		}
		throw failure;
	}

	private static void preserveStaging(ManagedGenerationJob job, Path staging) {
		if (!Files.exists(staging)) return;
		Path diagnostic = job.workDirectory().resolve("diagnostics/failed-staging");
		try {
			if (!Files.exists(diagnostic)) Files.move(staging, diagnostic, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException ignored) {
			// Leave the staging directory in place; TTL cleanup retries after Windows releases file handles.
		}
	}

	private void appendEvent(UUID jobId, GenerationJobEvent event) {
		CappedJsonLog log = eventLogs.get(jobId);
		if (log != null) {
			Map<String, Object> eventJson = Map.of(
					"jobId", jobId.toString(),
					"id", event.id(),
					"timestamp", event.timestamp().toString(),
					"state", event.state().name(),
					"completedTiles", event.completedTiles(),
					"totalTiles", event.totalTiles(),
					"message", event.message());
			log.append(eventJson);
		}
	}

	private void logTileAttempt(ManagedGenerationJob job, TileWork tile, int attempt,
			String outcome, String category, String message) {
		CappedJsonLog log = tileLogs.get(job.id());
		if (log == null) return;
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("timestamp", Instant.now().toString());
		value.put("jobId", job.id().toString());
		value.put("tileId", tile.tile().toString());
		value.put("attempt", attempt);
		value.put("outcome", outcome);
		if (category != null) value.put("category", category);
		if (message != null) value.put("message", sanitize(message));
		log.append(value);
	}

	private void writeDiagnostics(ManagedGenerationJob job, JobMetrics metrics) {
		ScheduledFuture<?> timeout = jobTimeouts.remove(job.id());
		if (timeout != null) timeout.cancel(false);
		try {
			Map<String, Object> summary = new LinkedHashMap<>();
			summary.put("schemaVersion", "1.0");
			summary.put("jobId", job.id().toString());
			summary.put("state", job.state().name());
			summary.put("createdAt", job.createdAt().toString());
			summary.put("updatedAt", job.updatedAt().toString());
			summary.put("deadline", job.deadline().toString());
			summary.put("configHash", GenerationResultWriter.configHash(job.spec()));
			summary.put("error", sanitize(job.error()));
			summary.put("metrics", metrics.snapshot(job.spec().tilingConfig().workerCount()));
			summary.put("runtime", Map.of("javaVersion", System.getProperty("java.version", "unknown"),
					"osName", System.getProperty("os.name", "unknown"),
					"osArch", System.getProperty("os.arch", "unknown"),
					"processors", Runtime.getRuntime().availableProcessors(),
					"maxHeapBytes", Runtime.getRuntime().maxMemory()));
			summary.put("resourcePolicy", Map.ofEntries(
					Map.entry("estimatedBytesPerBuilding", resourcePolicy.estimatedBytesPerBuilding()),
					Map.entry("minimumUsableDiskBytes", resourcePolicy.minimumUsableDiskBytes()),
					Map.entry("maximumJobBytes", resourcePolicy.maximumJobBytes()),
					Map.entry("maximumZipBytes", resourcePolicy.maximumZipBytes()),
					Map.entry("maximumFeaturesPerTile", resourcePolicy.maximumFeaturesPerTile()),
					Map.entry("jobTimeoutMillis", resourcePolicy.jobTimeout().toMillis()),
					Map.entry("tileTimeoutMillis", resourcePolicy.tileTimeout().toMillis()),
					Map.entry("retryBaseDelayMillis", resourcePolicy.retryBaseDelay().toMillis()),
					Map.entry("maximumLogBytes", resourcePolicy.maximumLogBytes()),
					Map.entry("reservedHeapBytes", resourcePolicy.reservedHeapBytes()),
					Map.entry("estimatedWorkerHeapBytes", resourcePolicy.estimatedWorkerHeapBytes())));
			Files.writeString(job.workDirectory().resolve("diagnostics/summary.json"),
					GSON.toJson(summary), UTF_8);
		} catch (IOException ignored) {
			// The in-memory status remains authoritative if diagnostics storage fails.
		}
	}

	private String sanitize(String value) {
		if (value == null) return null;
		return value.replace(storageRoot.toString(), "<storage>")
				.replaceAll("(?i)[a-z]:[\\\\/][^\\s,;]+", "<path>")
				.replace('\n', ' ').replace('\r', ' ');
	}

	private static ThreadFactory namedFactory(String prefix) {
		AtomicInteger sequence = new AtomicInteger();
		return runnable -> {
			Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
			thread.setDaemon(false);
			return thread;
		};
	}

	private static void deleteTree(Path root) throws IOException {
		if (root == null || !Files.exists(root)) return;
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.deleteIfExists(file);
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
				if (error != null) throw error;
				Files.deleteIfExists(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	@Override
	public void close() {
		jobs.values().stream().filter(job -> !job.state().terminal()).forEach(ManagedGenerationJob::cancel);
		heartbeats.shutdownNow();
		timeouts.shutdownNow();
		coordinators.shutdownNow();
		workers.shutdownNow();
	}

	private record TileExecution(TileRenderResult result, TileFailure failure, int attempts) {}
	private record TileBatch(List<TileRenderResult> successes, List<TileFailure> failures) {}
}
