package org.osm2world.buildingtiler.domain;

import java.util.List;

public record TilingConfig(
		int zoom,
		List<Integer> lods,
		int workerCount,
		int queueCapacity,
		int transientRetryCount,
		double crossTileBufferMeters,
		int largeBuildingTileSpanWarning,
		List<OutputFormat> outputFormats) {

	public static final int DEFAULT_ZOOM = 15;
	public static final List<Integer> MVP_LODS = List.of(2);

	public TilingConfig {
		if (zoom < 0 || zoom > 22) throw new IllegalArgumentException("zoom must be between 0 and 22");
		lods = lods == null || lods.isEmpty() ? MVP_LODS : List.copyOf(lods);
		if (!MVP_LODS.equals(lods)) {
			throw new IllegalArgumentException("M3 MVP supports the evidence-selected LOD set [2]");
		}
		if (workerCount < 1 || workerCount > 64) {
			throw new IllegalArgumentException("workerCount must be between 1 and 64");
		}
		if (queueCapacity < 1 || queueCapacity > 4096) {
			throw new IllegalArgumentException("queueCapacity must be between 1 and 4096");
		}
		if (transientRetryCount < 0 || transientRetryCount > 3) {
			throw new IllegalArgumentException("transientRetryCount must be between 0 and 3");
		}
		if (!Double.isFinite(crossTileBufferMeters) || crossTileBufferMeters != 0) {
			throw new IllegalArgumentException(
					"crossTileBufferMeters must be 0 for the centroid-owner/full-footprint strategy");
		}
		if (largeBuildingTileSpanWarning < 2 || largeBuildingTileSpanWarning > 10_000) {
			throw new IllegalArgumentException("largeBuildingTileSpanWarning must be between 2 and 10000");
		}
		outputFormats = outputFormats == null || outputFormats.isEmpty()
				? List.of(OutputFormat.THREE_D_TILES) : List.copyOf(outputFormats);
		if (!outputFormats.equals(List.of(OutputFormat.THREE_D_TILES))) {
			throw new IllegalArgumentException("Only the verified 3DTILES output format is enabled");
		}
	}

	public static TilingConfig defaults() {
		return defaults(Math.max(1, Runtime.getRuntime().availableProcessors() - 1), 128);
	}

	public static TilingConfig defaults(int workerCount, int queueCapacity) {
		return new TilingConfig(DEFAULT_ZOOM, MVP_LODS, workerCount, queueCapacity,
				1, 0, 4, List.of(OutputFormat.THREE_D_TILES));
	}
}
