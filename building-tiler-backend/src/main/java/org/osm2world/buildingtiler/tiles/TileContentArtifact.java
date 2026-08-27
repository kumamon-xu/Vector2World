package org.osm2world.buildingtiler.tiles;

public record TileContentArtifact(
		String tile,
		int lod,
		String tilesetPath,
		String glbPath) {
	public TileContentArtifact {
		if (tilesetPath.contains("\\") || glbPath.contains("\\")
				|| tilesetPath.startsWith("/") || glbPath.startsWith("/")) {
			throw new IllegalArgumentException("Tile content paths must be portable result-relative URIs");
		}
	}
}
