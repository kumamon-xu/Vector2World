package org.osm2world.buildingtiler.domain;

public record RuleVersion(String value) {

	public static final RuleVersion CURRENT = new RuleVersion("m2-rules-v1");

	public RuleVersion {
		if (value == null || !value.matches("[a-z0-9][a-z0-9._-]{2,63}")) {
			throw new IllegalArgumentException("Rule version must be a stable lowercase identifier");
		}
	}
}
