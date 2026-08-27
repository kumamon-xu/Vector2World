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
