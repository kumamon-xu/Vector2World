package org.osm2world.buildingtiler.tiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.osm2world.math.geo.TileNumber;
import org.osm2world.output.tileset.TilesetTreeUtil;
import org.osm2world.scene.mesh.LevelOfDetail;

public final class TilesetTreeAssembler {

	public void assemble(Path resultRoot, List<TileNumber> successfulTiles, List<Integer> lodNumbers)
			throws IOException {
		if (successfulTiles == null || successfulTiles.isEmpty()) {
			throw new IOException("Cannot build a tileset tree without a successful tile");
		}
		List<TileNumber> orderedTiles = successfulTiles.stream()
				.distinct().sorted(TileOwnershipPlanner.TILE_ORDER).toList();
		List<LevelOfDetail> lods = lodNumbers.stream().map(LevelOfDetail::fromInt).toList();
		if (lods.stream().anyMatch(value -> value == null)) throw new IOException("Invalid LOD set");
		TilesetTreeUtil.generateTilesetTree(resultRoot, orderedTiles, lods);
		Path root = resultRoot.resolve("tileset.json");
		if (!Files.isRegularFile(root)) throw new IOException("TilesetTreeUtil did not create root tileset.json");
		String json = Files.readString(root, StandardCharsets.UTF_8);
		if (json.contains("\\\\")) throw new IOException("Tileset tree contains Windows path separators");
	}

	public void assembleTileIds(Path resultRoot, List<String> successfulTileIds, List<Integer> lodNumbers)
			throws IOException {
		try {
			assemble(resultRoot, successfulTileIds.stream().map(TileNumber::new).toList(), lodNumbers);
		} catch (IllegalArgumentException exception) {
			throw new IOException("Invalid successful tile ID", exception);
		}
	}
}
