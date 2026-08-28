package org.osm2world.buildingtiler.tiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.osm2world.output.tileset.TilesetTreeUtil;
import org.osm2world.scene.mesh.LevelOfDetail;

/** Keeps OSM2World-specific tile path conventions behind the tiles adapter boundary. */
public final class TileArtifactPaths {

	private TileArtifactPaths() {}

	public static void deleteKnownLods(Path staging, TileWork tile) {
		for (int lodNumber : List.of(2, 3, 4)) {
			LevelOfDetail lod = LevelOfDetail.fromInt(lodNumber);
			try {
				Files.deleteIfExists(TilesetTreeUtil.tilePath(staging, tile.tile(), lod, ".tileset.json"));
				Files.deleteIfExists(TilesetTreeUtil.tilePath(staging, tile.tile(), lod, ".glb"));
			} catch (IOException ignored) {
				// Result validation catches an artifact that remains locked on Windows.
			}
		}
	}
}
