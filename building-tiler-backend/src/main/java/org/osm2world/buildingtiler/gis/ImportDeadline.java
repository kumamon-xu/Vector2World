package org.osm2world.buildingtiler.gis;

import java.time.Duration;

/** One monotonic timeout/cancellation budget shared by every phase of an import. */
public final class ImportDeadline {

	private final long deadlineNanos;

	private ImportDeadline(Duration timeout) {
		long now = System.nanoTime();
		long duration;
		try { duration = timeout.toNanos(); }
		catch (ArithmeticException exception) { duration = Long.MAX_VALUE; }
		deadlineNanos = duration >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + duration;
	}

	public static ImportDeadline start(Duration timeout) {
		if (timeout == null || timeout.isZero() || timeout.isNegative()) {
			throw new IllegalArgumentException("Import timeout must be positive");
		}
		return new ImportDeadline(timeout);
	}

	public void check(String phase) throws DatasetImportException {
		if (Thread.currentThread().isInterrupted()) {
			throw new DatasetImportException(DatasetErrorCode.IMPORT_CANCELLED,
					"Dataset import was cancelled during " + phase);
		}
		if (System.nanoTime() - deadlineNanos >= 0) {
			throw new DatasetImportException(DatasetErrorCode.IMPORT_TIMEOUT,
					"Dataset import timed out during " + phase);
		}
	}
}
