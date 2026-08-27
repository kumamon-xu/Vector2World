package org.osm2world.buildingtiler.gis;

import org.osm2world.buildingtiler.domain.HeightQualityStatistics;

final class HeightStatisticsAccumulator {

	private long valid;
	private long nullOrEmpty;
	private long nonNumeric;
	private long nonFinite;
	private long nonPositive;
	private long aboveMaximum;
	private double minimum = Double.POSITIVE_INFINITY;
	private double maximum = Double.NEGATIVE_INFINITY;
	private double sum;

	void accept(HeightValueParser.Result result) {
		switch (result.status()) {
			case VALID -> {
				valid++;
				minimum = Math.min(minimum, result.meters());
				maximum = Math.max(maximum, result.meters());
				sum += result.meters();
			}
			case NULL_OR_EMPTY -> nullOrEmpty++;
			case NON_NUMERIC -> nonNumeric++;
			case NON_FINITE -> nonFinite++;
			case NON_POSITIVE -> nonPositive++;
			case ABOVE_MAXIMUM -> aboveMaximum++;
		}
	}

	HeightQualityStatistics result() {
		return new HeightQualityStatistics(valid, nullOrEmpty, nonNumeric, nonFinite,
				nonPositive, aboveMaximum,
				valid == 0 ? null : minimum,
				valid == 0 ? null : maximum,
				valid == 0 ? null : sum / valid);
	}
}
