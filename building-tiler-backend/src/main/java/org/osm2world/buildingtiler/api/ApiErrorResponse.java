package org.osm2world.buildingtiler.api;

import java.time.Instant;
import java.util.Map;

public record ApiErrorResponse(
		String schemaVersion,
		Instant timestamp,
		int status,
		String code,
		String message,
		Map<String, Object> details) {
}
