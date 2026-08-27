package org.osm2world.buildingtiler.tiles;

import java.util.List;

import org.osm2world.buildingtiler.osm2world.Osm2WorldEngineAdapter;

public record TileRenderResult(
		String tile,
		int modeledBuildings,
		int meshCount,
		long vertexCount,
		long triangleCount,
		long outputBytes,
		long elapsedNanos,
		List<TileContentArtifact> contents,
		List<String> warnings,
		List<Osm2WorldEngineAdapter.FeatureFailure> featureFailures) {

	public TileRenderResult {
		contents = List.copyOf(contents);
		warnings = List.copyOf(warnings);
		featureFailures = List.copyOf(featureFailures);
	}
}
