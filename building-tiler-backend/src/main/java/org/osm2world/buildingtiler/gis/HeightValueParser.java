package org.osm2world.buildingtiler.gis;

import org.osm2world.buildingtiler.domain.HeightMapping;

public final class HeightValueParser {

	public enum Status { VALID, NULL_OR_EMPTY, NON_NUMERIC, NON_FINITE, NON_POSITIVE, ABOVE_MAXIMUM }

	public record Result(Status status, double meters) {
		public boolean valid() { return status == Status.VALID; }
	}

	private HeightValueParser() {}

	public static Result parse(Object value, HeightMapping mapping) {
		if (value == null) return new Result(Status.NULL_OR_EMPTY, Double.NaN);
		String text = value instanceof Number ? value.toString() : value.toString().trim();
		if (text.isEmpty()) return new Result(Status.NULL_OR_EMPTY, Double.NaN);

		double numeric;
		try {
			numeric = value instanceof Number number ? number.doubleValue() : Double.parseDouble(text);
		} catch (NumberFormatException exception) {
			return new Result(Status.NON_NUMERIC, Double.NaN);
		}
		if (!Double.isFinite(numeric)) return new Result(Status.NON_FINITE, Double.NaN);
		double meters = mapping.unit().toMeters(numeric);
		if (!Double.isFinite(meters)) return new Result(Status.NON_FINITE, Double.NaN);
		if (meters <= 0) return new Result(Status.NON_POSITIVE, meters);
		if (meters > mapping.maximumHeightMeters()) return new Result(Status.ABOVE_MAXIMUM, meters);
		return new Result(Status.VALID, meters);
	}
}
