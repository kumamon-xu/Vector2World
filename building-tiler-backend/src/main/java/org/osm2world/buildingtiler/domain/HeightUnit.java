package org.osm2world.buildingtiler.domain;

import java.util.Locale;

public enum HeightUnit {
	M(1.0), CM(0.01), MM(0.001), FT(0.3048);

	private final double metersPerUnit;

	HeightUnit(double metersPerUnit) {
		this.metersPerUnit = metersPerUnit;
	}

	public double toMeters(double value) {
		return value * metersPerUnit;
	}

	public static HeightUnit parse(String value) {
		if (value == null || value.isBlank()) return M;
		return valueOf(value.trim().toUpperCase(Locale.ROOT));
	}
}
