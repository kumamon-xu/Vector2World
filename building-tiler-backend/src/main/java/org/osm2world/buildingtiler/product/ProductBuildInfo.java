package org.osm2world.buildingtiler.product;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public record ProductBuildInfo(
		String version,
		String buildNumber,
		String gitSha,
		boolean gitDirty,
		String buildTime,
		boolean packaged,
		String osm2worldCommit,
		String ruleVersion,
		String presetVersion) {

	private static final ProductBuildInfo CURRENT = load();

	public static ProductBuildInfo current() { return CURRENT; }

	public Map<String, Object> asMap() {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("schemaVersion", "1.0");
		values.put("name", "Vector2World");
		values.put("version", version);
		values.put("buildNumber", buildNumber);
		values.put("gitSha", gitSha);
		values.put("gitDirty", gitDirty);
		values.put("buildTime", buildTime);
		values.put("packaged", packaged);
		values.put("osm2worldCommit", osm2worldCommit);
		values.put("ruleVersion", ruleVersion);
		values.put("presetVersion", presetVersion);
		return Map.copyOf(values);
	}

	private static ProductBuildInfo load() {
		Properties properties = new Properties();
		try (InputStream input = ProductBuildInfo.class.getResourceAsStream("/vector2world-build.properties")) {
			if (input != null) properties.load(input);
		} catch (IOException ignored) {
			// Defaults below keep development launches diagnostic instead of preventing startup.
		}
		return new ProductBuildInfo(
				value(properties, "product.version", "1.0.0-rc.1"),
				value(properties, "product.buildNumber", "dev"),
				value(properties, "product.gitSha", "unknown"),
				Boolean.parseBoolean(value(properties, "product.gitDirty", "true")),
				value(properties, "product.buildTime", "unknown"),
				Boolean.parseBoolean(value(properties, "product.packaged", "false")),
				value(properties, "product.osm2worldCommit", "bfa31df1124295721ec848273fbf93ab46b24d25"),
				value(properties, "product.ruleVersion", "m2-rules-v1"),
				value(properties, "product.presetVersion", "m2-presets-v1"));
	}

	private static String value(Properties properties, String name, String fallback) {
		String value = properties.getProperty(name, fallback).trim();
		return value.isEmpty() || value.startsWith("@") ? fallback : value;
	}
}
