package org.osm2world.buildingtiler.application;

import java.time.Instant;

public record GenerationJobEvent(
		long id,
		Instant timestamp,
		GenerationJobState state,
		int completedTiles,
		int totalTiles,
		String message) {
}
