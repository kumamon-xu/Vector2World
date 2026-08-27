package org.osm2world.buildingtiler.application;

public record TileFailure(
		String tile,
		String category,
		int attempts,
		boolean retryable,
		String message) {
}
