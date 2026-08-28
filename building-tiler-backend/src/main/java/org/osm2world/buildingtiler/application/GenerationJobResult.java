package org.osm2world.buildingtiler.application;

import java.nio.file.Path;
import java.util.List;

import org.osm2world.buildingtiler.tiles.TilesetValidator;

public record GenerationJobResult(
		Path resultDirectory,
		int plannedTiles,
		int successfulTiles,
		int failedTiles,
		boolean incomplete,
		int failedBuildings,
		int modeledBuildings,
		int meshCount,
		long vertexCount,
		long triangleCount,
		long outputBytes,
		String ownershipHash,
		List<String> tiles,
		List<TileFailure> tileFailures,
		List<String> warnings,
		List<JobArtifact> artifacts,
		TilesetValidator.ValidationResult validation) {

	public GenerationJobResult {
		tiles = List.copyOf(tiles);
		tileFailures = List.copyOf(tileFailures);
		warnings = List.copyOf(warnings);
		artifacts = List.copyOf(artifacts);
	}
}
