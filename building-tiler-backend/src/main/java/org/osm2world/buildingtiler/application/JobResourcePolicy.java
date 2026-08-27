package org.osm2world.buildingtiler.application;

import java.time.Duration;

/**
 * Hard resource boundaries for one generation job. Values are deliberately
 * explicit so a benchmark report can be reproduced against the same policy.
 */
public record JobResourcePolicy(
		long estimatedBytesPerBuilding,
		long minimumUsableDiskBytes,
		long maximumJobBytes,
		long maximumZipBytes,
		int maximumFeaturesPerTile,
		Duration jobTimeout,
		Duration tileTimeout,
		Duration retryBaseDelay,
		long maximumLogBytes,
		long reservedHeapBytes,
		long estimatedWorkerHeapBytes) {

	public JobResourcePolicy {
		if (estimatedBytesPerBuilding < 1 || minimumUsableDiskBytes < 0
				|| maximumJobBytes < 1 || maximumZipBytes < 1 || maximumLogBytes < 1024
				|| reservedHeapBytes < 0 || estimatedWorkerHeapBytes < 1) {
			throw new IllegalArgumentException("Resource byte limits must be positive");
		}
		if (maximumFeaturesPerTile < 1) {
			throw new IllegalArgumentException("maximumFeaturesPerTile must be positive");
		}
		if (invalid(jobTimeout) || invalid(tileTimeout) || invalid(retryBaseDelay)) {
			throw new IllegalArgumentException("Timeouts and retry delay must be positive");
		}
	}

	public static JobResourcePolicy defaults() {
		return new JobResourcePolicy(8L * 1024, 256L * 1024 * 1024,
				8L * 1024 * 1024 * 1024, 8L * 1024 * 1024 * 1024,
				100_000, Duration.ofHours(6), Duration.ofMinutes(30),
				Duration.ofMillis(100), 8L * 1024 * 1024,
				128L * 1024 * 1024, 128L * 1024 * 1024);
	}

	private static boolean invalid(Duration value) {
		return value == null || value.isZero() || value.isNegative();
	}
}
