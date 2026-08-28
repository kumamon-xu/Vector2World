package org.osm2world.buildingtiler.gis;

/** Prevents a large import from consuming the JVM's emergency heap reserve. */
final class ImportMemoryGuard {

	private static final long MINIMUM_RESERVE_BYTES = 128L * 1024 * 1024;
	private static final int CHECK_INTERVAL = 1024;

	private ImportMemoryGuard() {}

	static void check(long retainedFeatures, String phase) throws DatasetImportException {
		if (retainedFeatures > CHECK_INTERVAL && retainedFeatures % CHECK_INTERVAL != 0) return;
		Runtime runtime = Runtime.getRuntime();
		long used = runtime.totalMemory() - runtime.freeMemory();
		long available = Math.max(0, runtime.maxMemory() - used);
		long reserve = Math.min(runtime.maxMemory() / 3,
				Math.max(MINIMUM_RESERVE_BYTES, runtime.maxMemory() / 10));
		if (available < reserve) {
			throw new DatasetImportException(DatasetErrorCode.IMPORT_RESOURCE_LIMIT,
					"Dataset import stopped during " + phase + " after retaining " + retainedFeatures
							+ " features because JVM heap reserve fell below " + reserve + " bytes");
		}
	}
}
