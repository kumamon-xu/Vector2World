package org.osm2world.buildingtiler.application;

public enum GenerationJobState {
	CREATED,
	VALIDATING,
	PREPARING,
	TILING,
	MODELING,
	BUILDING_TILESET,
	VALIDATING_RESULT,
	COMPLETED,
	COMPLETED_WITH_WARNINGS,
	FAILED,
	CANCELLED;

	public boolean terminal() {
		return this == COMPLETED || this == COMPLETED_WITH_WARNINGS || this == FAILED || this == CANCELLED;
	}
}
