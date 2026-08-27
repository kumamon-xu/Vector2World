package org.osm2world.buildingtiler.domain;

import java.util.Locale;

public enum OutputFormat {
	THREE_D_TILES("3DTILES");

	private final String value;

	OutputFormat(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

	public static OutputFormat parse(String value) {
		if (value == null || value.isBlank()) return THREE_D_TILES;
		String normalized = value.trim().toUpperCase(Locale.ROOT).replace("_", "").replace("-", "");
		if ("3DTILES".equals(normalized)) return THREE_D_TILES;
		throw new IllegalArgumentException("Unsupported output format: " + value
				+ "; verified formats: 3DTILES");
	}
}
