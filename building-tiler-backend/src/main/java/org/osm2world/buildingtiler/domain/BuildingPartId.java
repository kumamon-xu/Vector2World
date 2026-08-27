package org.osm2world.buildingtiler.domain;

public record BuildingPartId(String sourceFeatureId, int partIndex) {

	public BuildingPartId {
		if (sourceFeatureId == null || sourceFeatureId.isBlank()) {
			throw new IllegalArgumentException("Source feature id must not be blank");
		}
		if (partIndex < 0) throw new IllegalArgumentException("Part index must not be negative");
	}

	@Override
	public String toString() {
		return sourceFeatureId + ":part:" + partIndex;
	}
}
