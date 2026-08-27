package org.osm2world.buildingtiler.domain;

import java.util.Locale;

public enum StylePresetId {
	NEUTRAL_CITY("neutral-city"),
	WARM_RESIDENTIAL("warm-residential"),
	MODERN_CITY("modern-city"),
	INDUSTRIAL("industrial");

	private final String value;

	StylePresetId(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

	public static StylePresetId parse(String value) {
		if (value == null || value.isBlank()) return NEUTRAL_CITY;
		String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
		for (StylePresetId preset : values()) {
			if (preset.value.equals(normalized)) return preset;
		}
		throw new IllegalArgumentException("Unknown style preset: " + value);
	}
}
