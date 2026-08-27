package org.osm2world.buildingtiler.osm2world;

public final class ModelEngineException extends RuntimeException {

	private final ModelFailureCategory category;

	public ModelEngineException(ModelFailureCategory category, String message, Throwable cause) {
		super(message, cause);
		this.category = category;
	}

	public ModelFailureCategory category() {
		return category;
	}
}
