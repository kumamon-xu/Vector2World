package org.osm2world.buildingtiler.domain;

import java.util.List;

public record FieldMetadata(
		String name,
		String type,
		long presentCount,
		long nullOrEmptyCount,
		long numericCount,
		List<String> sampleValues) {

	public FieldMetadata {
		if (name == null || name.isBlank()) throw new IllegalArgumentException("Field name must not be blank");
		if (type == null || type.isBlank()) throw new IllegalArgumentException("Field type must not be blank");
		if (presentCount < 0 || nullOrEmptyCount < 0 || numericCount < 0) {
			throw new IllegalArgumentException("Field counts must not be negative");
		}
		sampleValues = sampleValues == null ? List.of() : List.copyOf(sampleValues);
	}
}
