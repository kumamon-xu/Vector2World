package org.osm2world.buildingtiler.domain;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Immutable attribute map which preserves null source values and can be shared across projections. */
public final class ImmutableAttributes extends AbstractMap<String, Object> {

	private final Map<String, Object> values;

	private ImmutableAttributes(Map<String, Object> source) {
		values = Collections.unmodifiableMap(new LinkedHashMap<>(source));
	}

	public static Map<String, Object> copyOf(Map<String, Object> source) {
		if (source == null || source.isEmpty()) return Map.of();
		if (source instanceof ImmutableAttributes) return source;
		return new ImmutableAttributes(source);
	}

	@Override public Set<Entry<String, Object>> entrySet() { return values.entrySet(); }
	@Override public Object get(Object key) { return values.get(key); }
	@Override public boolean containsKey(Object key) { return values.containsKey(key); }
	@Override public int size() { return values.size(); }
}
