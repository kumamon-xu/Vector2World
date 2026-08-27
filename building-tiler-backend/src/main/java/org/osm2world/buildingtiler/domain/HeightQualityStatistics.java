package org.osm2world.buildingtiler.domain;

public record HeightQualityStatistics(
		long valid,
		long nullOrEmpty,
		long nonNumeric,
		long nonFinite,
		long nonPositive,
		long aboveMaximum,
		Double minimumMeters,
		Double maximumMeters,
		Double averageMeters) {

	public static HeightQualityStatistics empty() {
		return new HeightQualityStatistics(0, 0, 0, 0, 0, 0,
				null, null, null);
	}

	public long invalid() {
		return nullOrEmpty + nonNumeric + nonFinite + nonPositive + aboveMaximum;
	}
}
