package org.osm2world.buildingtiler.tiles;

public final class TileRenderException extends Exception {

	private final TileFailureCategory category;

	public TileRenderException(TileFailureCategory category, String message, Throwable cause) {
		super(message, cause);
		this.category = category == null ? TileFailureCategory.INTERNAL : category;
	}

	public TileRenderException(TileFailureCategory category, String message) {
		this(category, message, null);
	}

	public TileFailureCategory category() {
		return category;
	}

	public boolean retryable() {
		return category.retryable();
	}
}
