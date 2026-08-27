package org.osm2world.buildingtiler.domain;

public record FootprintMetrics(
		double areaSquareMeters,
		double perimeterMeters,
		double compactness,
		double convexity,
		int vertexCount,
		double aspectRatio,
		double orthogonality,
		int partCount,
		boolean irregular) {

	public FootprintMetrics {
		if (!Double.isFinite(areaSquareMeters) || areaSquareMeters <= 0) {
			throw new IllegalArgumentException("Footprint area must be finite and positive");
		}
		if (!Double.isFinite(perimeterMeters) || perimeterMeters <= 0) {
			throw new IllegalArgumentException("Footprint perimeter must be finite and positive");
		}
	}
}
