package org.osm2world.buildingtiler.domain;

public record FootprintThresholds(
		double minimumCompactness,
		double minimumConvexity,
		double minimumOrthogonality,
		int maximumSimpleVertices,
		double maximumPitchedAspectRatio) {

	public FootprintThresholds {
		unitInterval(minimumCompactness, "minimumCompactness");
		unitInterval(minimumConvexity, "minimumConvexity");
		unitInterval(minimumOrthogonality, "minimumOrthogonality");
		if (maximumSimpleVertices < 4 || maximumSimpleVertices > 1000) {
			throw new IllegalArgumentException("maximumSimpleVertices must be between 4 and 1000");
		}
		if (!Double.isFinite(maximumPitchedAspectRatio) || maximumPitchedAspectRatio < 1
				|| maximumPitchedAspectRatio > 100) {
			throw new IllegalArgumentException("maximumPitchedAspectRatio must be between 1 and 100");
		}
	}

	public static FootprintThresholds defaults() {
		return new FootprintThresholds(0.45, 0.82, 0.55, 12, 4.0);
	}

	private static void unitInterval(double value, String name) {
		if (!Double.isFinite(value) || value < 0 || value > 1) {
			throw new IllegalArgumentException(name + " must be between 0 and 1");
		}
	}
}
