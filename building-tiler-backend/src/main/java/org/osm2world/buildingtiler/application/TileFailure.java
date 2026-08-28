package org.osm2world.buildingtiler.application;

import java.util.List;

public record TileFailure(
		String tile,
		String category,
		int attempts,
		boolean retryable,
		int failedBuildings,
		List<String> failedFeatureIds,
		String message) {

	public TileFailure {
		if (failedBuildings < 0) throw new IllegalArgumentException("failedBuildings must be non-negative");
		failedFeatureIds = failedFeatureIds == null ? List.of() : List.copyOf(failedFeatureIds);
		if (failedFeatureIds.size() > failedBuildings) {
			throw new IllegalArgumentException("failedFeatureIds cannot exceed failedBuildings");
		}
	}

	public TileFailure(String tile, String category, int attempts, boolean retryable,
			int failedBuildings, String message) {
		this(tile, category, attempts, retryable, failedBuildings, List.of(), message);
	}

	public TileFailure(String tile, String category, int attempts, boolean retryable, String message) {
		this(tile, category, attempts, retryable, 0, List.of(), message);
	}
}
