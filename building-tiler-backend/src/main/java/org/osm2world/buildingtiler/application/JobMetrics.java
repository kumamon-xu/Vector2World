package org.osm2world.buildingtiler.application;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class JobMetrics {

	private final Map<String, Long> phasesNanos = new ConcurrentHashMap<>();
	private final AtomicLong peakHeapBytes = new AtomicLong();
	private final AtomicLong emittedBytes = new AtomicLong();
	private final AtomicInteger retryAttempts = new AtomicInteger();
	private final AtomicInteger queuedTiles = new AtomicInteger();
	private final AtomicInteger runningTiles = new AtomicInteger();
	private final AtomicInteger inFlightTiles = new AtomicInteger();
	private final AtomicInteger maxQueuedTiles = new AtomicInteger();
	private final AtomicInteger maxRunningTiles = new AtomicInteger();
	private final AtomicInteger maxInFlightTiles = new AtomicInteger();
	private final AtomicInteger maxGlobalQueueDepth = new AtomicInteger();
	private final AtomicLong tileWaitNanos = new AtomicLong();
	private final AtomicLong tileExecutionNanos = new AtomicLong();
	private final AtomicInteger startedTiles = new AtomicInteger();
	private final long gcCollectionsAtStart = gcCollections();
	private final long gcTimeMillisAtStart = gcTimeMillis();

	long start() {
		sampleHeap();
		return System.nanoTime();
	}

	void finish(String phase, long startedNanos) {
		phasesNanos.merge(phase, Math.max(0, System.nanoTime() - startedNanos), Long::sum);
		sampleHeap();
	}

	void emitted(long bytes) {
		emittedBytes.addAndGet(Math.max(0, bytes));
		sampleHeap();
	}

	void retried() {
		retryAttempts.incrementAndGet();
	}

	void tileQueued(int globalQueueDepth) {
		int queued = queuedTiles.incrementAndGet();
		int inFlight = inFlightTiles.incrementAndGet();
		maxQueuedTiles.accumulateAndGet(queued, Math::max);
		maxInFlightTiles.accumulateAndGet(inFlight, Math::max);
		maxGlobalQueueDepth.accumulateAndGet(Math.max(0, globalQueueDepth), Math::max);
	}

	long tileStarted(long queuedAtNanos) {
		queuedTiles.updateAndGet(value -> Math.max(0, value - 1));
		int running = runningTiles.incrementAndGet();
		maxRunningTiles.accumulateAndGet(running, Math::max);
		startedTiles.incrementAndGet();
		long started = System.nanoTime();
		tileWaitNanos.addAndGet(Math.max(0, started - queuedAtNanos));
		return started;
	}

	void tileFinished(long startedNanos) {
		tileExecutionNanos.addAndGet(Math.max(0, System.nanoTime() - startedNanos));
		runningTiles.updateAndGet(value -> Math.max(0, value - 1));
		inFlightTiles.updateAndGet(value -> Math.max(0, value - 1));
	}

	long emittedBytes() { return emittedBytes.get(); }

	Map<String, Object> snapshot(int effectiveWorkers) {
		sampleHeap();
		Map<String, Long> orderedPhases = new LinkedHashMap<>();
		phasesNanos.entrySet().stream().sorted(Map.Entry.comparingByKey())
				.forEach(entry -> orderedPhases.put(entry.getKey(), entry.getValue()));
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("phaseNanos", orderedPhases);
		result.put("peakHeapBytes", peakHeapBytes.get());
		result.put("gcCollections", Math.max(0, gcCollections() - gcCollectionsAtStart));
		result.put("gcTimeMillis", Math.max(0, gcTimeMillis() - gcTimeMillisAtStart));
		result.put("emittedTileBytes", emittedBytes.get());
		result.put("retryAttempts", retryAttempts.get());
		result.put("effectiveWorkers", effectiveWorkers);
		result.put("maxQueuedTiles", maxQueuedTiles.get());
		result.put("maxRunningTiles", maxRunningTiles.get());
		result.put("maxInFlightTiles", maxInFlightTiles.get());
		result.put("maxGlobalQueueDepth", maxGlobalQueueDepth.get());
		result.put("tileWaitNanos", tileWaitNanos.get());
		result.put("tileExecutionNanos", tileExecutionNanos.get());
		result.put("startedTiles", startedTiles.get());
		return result;
	}

	private void sampleHeap() {
		Runtime runtime = Runtime.getRuntime();
		long used = runtime.totalMemory() - runtime.freeMemory();
		peakHeapBytes.accumulateAndGet(used, Math::max);
	}

	private static long gcCollections() {
		return ManagementFactory.getGarbageCollectorMXBeans().stream()
				.mapToLong(GarbageCollectorMXBean::getCollectionCount).filter(value -> value >= 0).sum();
	}

	private static long gcTimeMillis() {
		return ManagementFactory.getGarbageCollectorMXBeans().stream()
				.mapToLong(GarbageCollectorMXBean::getCollectionTime).filter(value -> value >= 0).sum();
	}
}
