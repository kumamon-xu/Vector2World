package org.osm2world.buildingtiler.gis;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKBWriter;

final class StableIdGenerator {

	private StableIdGenerator() {}

	static String baseId(String sourceId, Geometry geometry, Map<String, Object> properties) {
		if (sourceId != null && !sourceId.isBlank()) return "source:" + sourceId.trim();
		MessageDigest digest = sha256();
		Geometry normalized = geometry.copy();
		normalized.normalize();
		digest.update(new WKBWriter(2, true).write(normalized));
		for (Map.Entry<String, Object> entry : new TreeMap<>(properties).entrySet()) {
			digest.update((byte) 0);
			digest.update(entry.getKey().getBytes(UTF_8));
			digest.update((byte) '=');
			digest.update(canonicalValue(entry.getValue()).getBytes(UTF_8));
		}
		return "hash:" + HexFormat.of().formatHex(digest.digest());
	}

	static String collisionSuffix(String baseId, Geometry geometry, Map<String, Object> properties) {
		MessageDigest digest = sha256();
		digest.update(baseId.getBytes(UTF_8));
		digest.update(new WKBWriter(2, true).write(geometry));
		for (Map.Entry<String, Object> entry : new TreeMap<>(properties).entrySet()) {
			digest.update(entry.getKey().getBytes(UTF_8));
			digest.update(canonicalValue(entry.getValue()).getBytes(UTF_8));
		}
		return HexFormat.of().formatHex(digest.digest(), 0, 6);
	}

	private static String canonicalValue(Object value) {
		if (value == null) return "null";
		if (value instanceof Number number) {
			double numeric = number.doubleValue();
			return Double.isFinite(numeric) ? java.math.BigDecimal.valueOf(numeric).stripTrailingZeros().toPlainString()
					: number.toString();
		}
		return value.toString();
	}

	private static MessageDigest sha256() {
		try { return MessageDigest.getInstance("SHA-256"); }
		catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
	}
}
