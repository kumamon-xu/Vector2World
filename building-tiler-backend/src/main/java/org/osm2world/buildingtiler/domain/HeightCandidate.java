package org.osm2world.buildingtiler.domain;

public record HeightCandidate(
		String fieldName,
		double score,
		HeightQualityStatistics qualityAssumingMeters) {

	public HeightCandidate {
		if (fieldName == null || fieldName.isBlank()) throw new IllegalArgumentException("Field name must not be blank");
		if (!Double.isFinite(score) || score < 0 || score > 1) {
			throw new IllegalArgumentException("Candidate score must be between 0 and 1");
		}
		if (qualityAssumingMeters == null) throw new IllegalArgumentException("Height quality is required");
	}
}
