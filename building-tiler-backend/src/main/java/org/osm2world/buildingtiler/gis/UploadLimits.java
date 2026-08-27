package org.osm2world.buildingtiler.gis;

import java.time.Duration;

public record UploadLimits(
		long maximumUploadBytes,
		long maximumZipUncompressedBytes,
		int maximumZipEntries,
		double maximumCompressionRatio,
		Duration datasetTtl) {

	public UploadLimits {
		if (maximumUploadBytes <= 0 || maximumZipUncompressedBytes <= 0
				|| maximumZipEntries <= 0 || maximumCompressionRatio <= 1) {
			throw new IllegalArgumentException("Upload limits must be positive");
		}
		datasetTtl = datasetTtl == null ? Duration.ofHours(24) : datasetTtl;
	}

	public static UploadLimits defaults() {
		return new UploadLimits(256L * 1024 * 1024, 1024L * 1024 * 1024,
				20_000, 200.0, Duration.ofHours(24));
	}
}
