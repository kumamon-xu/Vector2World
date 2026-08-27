package org.osm2world.buildingtiler.domain;

public record HeightMapping(
		String fieldName,
		HeightUnit unit,
		InvalidHeightPolicy invalidPolicy,
		double maximumHeightMeters) {

	public static final double DEFAULT_MAXIMUM_HEIGHT_METERS = 10_000.0;

	public HeightMapping {
		if (fieldName == null || fieldName.isBlank()) {
			throw new IllegalArgumentException("Height field name must not be blank");
		}
		unit = unit == null ? HeightUnit.M : unit;
		invalidPolicy = invalidPolicy == null ? InvalidHeightPolicy.SKIP : invalidPolicy;
		if (!Double.isFinite(maximumHeightMeters) || maximumHeightMeters <= 0) {
			throw new IllegalArgumentException("Maximum height must be a finite positive number of meters");
		}
	}

	public HeightMapping(String fieldName, HeightUnit unit) {
		this(fieldName, unit, InvalidHeightPolicy.SKIP, DEFAULT_MAXIMUM_HEIGHT_METERS);
	}
}
