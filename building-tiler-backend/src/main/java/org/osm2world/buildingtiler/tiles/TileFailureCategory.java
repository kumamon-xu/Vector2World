package org.osm2world.buildingtiler.tiles;

public enum TileFailureCategory {
	DATA(false),
	GEOMETRY(false),
	IO_TRANSIENT(true),
	RESOURCE(true),
	INTERNAL(false);

	private final boolean retryable;

	TileFailureCategory(boolean retryable) {
		this.retryable = retryable;
	}

	public boolean retryable() {
		return retryable;
	}
}
