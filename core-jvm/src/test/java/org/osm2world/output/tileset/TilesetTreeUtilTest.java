package org.osm2world.output.tileset;

import static java.lang.Math.toRadians;
import static java.util.Collections.emptyList;
import static org.junit.Assert.*;
import static org.osm2world.output.tileset.TilesetTreeUtil.generateTilesetTree;
import static org.osm2world.output.tileset.TilesetTreeUtil.smallestCommonAncestor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.junit.Test;
import org.osm2world.math.geo.LatLonBounds;
import org.osm2world.math.geo.TileNumber;
import org.osm2world.output.tileset.tiles_data.TilesetAsset;
import org.osm2world.output.tileset.tiles_data.TilesetEntry;
import org.osm2world.output.tileset.tiles_data.TilesetParentEntry;
import org.osm2world.output.tileset.tiles_data.TilesetRoot;
import org.osm2world.util.platform.json.JsonImplementationJvm;
import org.osm2world.util.platform.json.JsonUtil;
import org.osm2world.util.test.TestFileUtil;

public class TilesetTreeUtilTest {

	static {
		JsonImplementationJvm.register();
	}

	@Test
	public void testSmallestCommonAncestorSingleTile() {
		TileNumber tile = new TileNumber(13, 4402, 2828);
		assertEquals(tile, smallestCommonAncestor(List.of(tile)));
	}

	@Test
	public void testSmallestCommonAncestorIdenticalTiles() {
		TileNumber tile = new TileNumber(13, 334, 999);
		assertEquals(tile, smallestCommonAncestor(List.of(tile, tile, tile)));
	}

	@Test
	public void testSmallestCommonAncestorSiblings() {

		/* the four children of tile 13/4402/2828 */

		assertEquals(new TileNumber(13, 4402, 2828), smallestCommonAncestor(List.of(
				new TileNumber(14, 8804, 5656),
				new TileNumber(14, 8805, 5656),
				new TileNumber(14, 8804, 5657),
				new TileNumber(14, 8805, 5657))));

		/* two of them are enough as long as they differ in both x and y */

		assertEquals(new TileNumber(13, 4402, 2828), smallestCommonAncestor(List.of(
				new TileNumber(14, 8804, 5657),
				new TileNumber(14, 8805, 5656))));

	}

	@Test
	public void testSmallestCommonAncestorNeighborsWithDistantAncestor() {

		/* neighboring tiles can be on opposite sides of a boundary at a low zoom level */

		assertEquals(new TileNumber(0, 0, 0), smallestCommonAncestor(List.of(
				new TileNumber(13, 4095, 2828),
				new TileNumber(13, 4096, 2828))));

	}

	@Test
	public void testSmallestCommonAncestorDifferentZoomLevels() {

		/* a tile and one of its descendants: the ancestor is the tile itself */

		assertEquals(new TileNumber(13, 4402, 2828), smallestCommonAncestor(List.of(
				new TileNumber(13, 4402, 2828),
				new TileNumber(15, 17608, 11312),
				new TileNumber(17, 70435, 45263))));

		/* order of the input must not matter */

		assertEquals(new TileNumber(13, 4402, 2828), smallestCommonAncestor(List.of(
				new TileNumber(17, 70435, 45263),
				new TileNumber(15, 17608, 11312),
				new TileNumber(13, 4402, 2828))));

		/* the result can be an ancestor of all input tiles */

		assertEquals(new TileNumber(12, 2201, 1414), smallestCommonAncestor(List.of(
				new TileNumber(13, 4402, 2828),
				new TileNumber(15, 17612, 11312))));

	}

	@Test
	public void testSmallestCommonAncestorZoom0() {
		TileNumber tile = new TileNumber(0, 0, 0);
		assertEquals(tile, smallestCommonAncestor(List.of(tile)));
		assertEquals(tile, smallestCommonAncestor(List.of(tile, new TileNumber(9, 42, 314))));
	}

	@Test
	public void testSmallestCommonAncestorEntireWorld() {

		/* the four tiles in the corners of the map at zoom level 5 */

		assertEquals(new TileNumber(0, 0, 0), smallestCommonAncestor(List.of(
				new TileNumber(5, 0, 0),
				new TileNumber(5, 31, 0),
				new TileNumber(5, 0, 31),
				new TileNumber(5, 31, 31))));

	}

	@Test(expected = IllegalArgumentException.class)
	public void testSmallestCommonAncestorEmpty() {
		smallestCommonAncestor(emptyList());
	}

	@Test
	public void testGenerateTilesetTreeSingleTile() throws IOException {

		var tile = new TileNumber(15, 17608, 11312);

		Path dir = TestFileUtil.createTempDirectory().toPath();
		writeTileTilesets(dir, List.of(tile));
		generateTilesetTree(dir, List.of(tile));

		/* the tile is its own smallest common ancestor, so the root refers to it directly */

		TilesetRoot tileset = readTileset(dir.resolve("tileset.json"));

		assertEquals("1.1", tileset.getAsset().getVersion());
		assertEquals(25.0, tileset.getGeometricError().doubleValue(), 1e-9);

		TilesetEntry root = tileset.getRoot();
		assertRegion(root, tile);
		assertEquals(25.0, root.getGeometricError().doubleValue(), 1e-9);
		assertEquals("15/17608/11312.tileset.json", root.getContent().getUri());
		assertNull(root.getChildren());

		assertFalse(Files.exists(dir.resolve("index")));

	}

	@Test
	public void testGenerateTilesetTreeSingleFile() throws IOException {

		var tile0 = new TileNumber(15, 17608, 11312);
		var tile1 = new TileNumber(15, 17609, 11312);

		Path dir = TestFileUtil.createTempDirectory().toPath();
		writeTileTilesets(dir, List.of(tile0, tile1));
		generateTilesetTree(dir, List.of(tile0, tile1));

		/* both tiles share a parent at zoom 14, so the whole tree fits into a single file */

		assertFalse(Files.exists(dir.resolve("index")));

		TilesetRoot tileset = readTileset(dir.resolve("tileset.json"));

		TilesetEntry root = tileset.getRoot();
		assertRegion(root, tile0, tile1);
		assertEquals(50.0, root.getGeometricError().doubleValue(), 1e-9);
		assertEquals(50.0, tileset.getGeometricError().doubleValue(), 1e-9);
		assertNull(root.getContent());

		/* only the two tiles which exist are present, not all four children of the zoom 14 tile */

		assertEquals(2, root.getChildren().size());

		for (TilesetEntry child : root.getChildren()) {
			assertEquals(25.0, child.getGeometricError().doubleValue(), 1e-9);
			assertNull(child.getChildren());
		}

		assertEquals(List.of("15/17608/11312.tileset.json", "15/17609/11312.tileset.json"),
				root.getChildren().stream().map(it -> it.getContent().getUri()).sorted().toList());

		assertRegion(root.getChildren().get(0), tile0);
		assertRegion(root.getChildren().get(1), tile1);

	}

	@Test
	public void testGenerateTilesetTreeMultipleFiles() throws IOException {

		var tile0 = new TileNumber(15, 17608, 11312);
		var tile1 = new TileNumber(15, 17620, 11312);

		Path dir = TestFileUtil.createTempDirectory().toPath();
		writeTileTilesets(dir, List.of(tile0, tile1));
		generateTilesetTree(dir, List.of(tile0, tile1));

		/* the smallest common ancestor is at zoom 10, i.e. 5 levels above the tiles.
		 * With at most 4 levels per file, two files are needed, so the 5 levels are split 3 + 2. */

		TilesetRoot tileset = readTileset(dir.resolve("tileset.json"));

		TilesetEntry entry = tileset.getRoot();
		assertRegion(entry, tile0, tile1);
		assertEquals(25.0 * 32, entry.getGeometricError().doubleValue(), 1e-9);

		/* descend the three levels within the root file.
		 * The tiles are in different zoom 11 tiles, so the tree branches immediately below the root. */

		assertNull(entry.getContent());
		assertEquals(2, entry.getChildren().size());
		entry = entry.getChildren().get(0);
		assertEquals(25.0 * 16, entry.getGeometricError().doubleValue(), 1e-9);

		for (int zoom = 12; zoom <= 13; zoom++) {
			assertNull("no content above the split at zoom 13", entry.getContent());
			assertEquals(1, entry.getChildren().size());
			entry = entry.getChildren().get(0);
			assertEquals(25.0 * (1 << (15 - zoom)), entry.getGeometricError().doubleValue(), 1e-9);
		}

		/* the zoom 13 tile refers to the file continuing the tree as an external tileset */

		var splitTile = new TileNumber(13, 4402, 2828);
		assertRegion(entry, tile0);
		assertEquals("index/13/4402/2828.tileset.json", entry.getContent().getUri());
		assertNull(entry.getChildren());

		/* the referenced file repeats the tile it is referenced from, and leads to the actual tile */

		TilesetRoot subTileset = readTileset(dir.resolve("index/13/4402/2828.tileset.json"));

		TilesetEntry subRoot = subTileset.getRoot();
		assertRegion(subRoot, tile0);
		assertEquals(100.0, subRoot.getGeometricError().doubleValue(), 1e-9);
		assertEquals(100.0, subTileset.getGeometricError().doubleValue(), 1e-9);

		assertEquals(1, subRoot.getChildren().size());
		TilesetEntry leaf = subRoot.getChildren().get(0).getChildren().get(0);
		assertRegion(leaf, tile0);
		assertEquals("../../../15/17608/11312.tileset.json", leaf.getContent().getUri());

		/* the second tile is reached through a file of its own */

		assertTrue(Files.exists(dir.resolve("index/13/4405/2828.tileset.json")));

	}

	@Test
	public void testGenerateTilesetTreeDuplicateTiles() throws IOException {

		var tile = new TileNumber(15, 17608, 11312);

		Path dir = TestFileUtil.createTempDirectory().toPath();
		writeTileTilesets(dir, List.of(tile));
		generateTilesetTree(dir, List.of(tile, tile));

		assertEquals("15/17608/11312.tileset.json",
				readTileset(dir.resolve("tileset.json")).getRoot().getContent().getUri());

	}

	@Test(expected = IllegalArgumentException.class)
	public void testGenerateTilesetTreeNestedTiles() throws IOException {
		List<TileNumber> tiles = List.of(new TileNumber(13, 4402, 2828), new TileNumber(15, 17608, 11312));
		Path dir = TestFileUtil.createTempDirectory().toPath();
		writeTileTilesets(dir, tiles);
		generateTilesetTree(dir, tiles);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testGenerateTilesetTreeEmpty() throws IOException {
		Path dir = TestFileUtil.createTempDirectory().toPath();
		generateTilesetTree(dir, emptyList());
	}

	@Test
	public void testGenerateTilesetTreeUsesContentBounds() throws IOException {

		var tile0 = new TileNumber(15, 17608, 11312);
		var tile1 = new TileNumber(15, 17609, 11313);

		Path dir = TestFileUtil.createTempDirectory().toPath();
		writeTileTilesets(dir, List.of(tile0, tile1));
		generateTilesetTree(dir, List.of(tile0, tile1));

		TilesetEntry root = readTileset(dir.resolve("tileset.json")).getRoot();
		double[] region = root.getBoundingVolume().getRegion();

		/* the bounds are those of the content, not those of the zoom 14 tile containing both tiles */

		LatLonBounds tileBounds = new TileNumber(14, 8804, 5656).latLonBounds();

		assertTrue("south of the content bounds is north of the south edge of the tile",
				region[1] > toRadians(tileBounds.minlat));
		assertEquals(toRadians(tileBounds.maxlat), region[3], 1e-12);

		/* the height range is the union of the heights of the two tiles, rather than a fixed range */

		assertEquals(-1, region[4], 1e-12);
		assertEquals(Math.max(contentRegion(tile0)[5], contentRegion(tile1)[5]), region[5], 1e-12);
		assertTrue("height range is tight", region[5] - region[4] < 100);

	}

	@Test
	public void testGenerateTilesetTreeSkipsMissingTiles() throws IOException {

		var tile0 = new TileNumber(15, 17608, 11312);
		var tile1 = new TileNumber(15, 17609, 11312);
		var missingTile = new TileNumber(15, 17609, 11313);

		/* the tileset of one of the requested tiles is missing, e.g. because the tile failed to render */

		Path dir = TestFileUtil.createTempDirectory().toPath();
		writeTileTilesets(dir, List.of(tile0, tile1));
		generateTilesetTree(dir, List.of(tile0, tile1, missingTile));

		/* the missing tile is left out of the tree instead of being referenced but unavailable */

		var reachedTiles = new HashSet<TileNumber>();
		walk(dir.resolve("tileset.json"), dir, null, reachedTiles);

		assertEquals(Set.of(tile0, tile1), reachedTiles);

	}

	@Test(expected = IOException.class)
	public void testGenerateTilesetTreeNoTilesExist() throws IOException {
		Path dir = TestFileUtil.createTempDirectory().toPath();
		generateTilesetTree(dir, List.of(new TileNumber(15, 17608, 11312)));
	}

	@Test
	public void testGenerateTilesetTreeStructure() throws IOException {

		List<TileNumber> tiles = TileNumber.tilesForBounds(15,
				new LatLonBounds(48.53, 13.38, 48.61, 13.51));

		assertTrue("test covers several tiles", tiles.size() > 20);

		Path dir = TestFileUtil.createTempDirectory().toPath();
		writeTileTilesets(dir, tiles);
		generateTilesetTree(dir, tiles);

		/* walking the tree from the entry point must reach every tile exactly once,
		 * and bounding volumes and geometric errors must be consistent along the way */

		var reachedTiles = new HashSet<TileNumber>();
		walk(dir.resolve("tileset.json"), dir, null, reachedTiles);

		assertEquals(Set.copyOf(tiles), reachedTiles);

	}

	/** recursively checks a tileset.json and everything it refers to, collecting the tiles it makes reachable */
	private static void walk(Path file, Path dir, @Nullable TilesetEntry parent, Set<TileNumber> reachedTiles)
			throws IOException {

		TilesetRoot tileset = readTileset(file);
		assertEquals("1.1", tileset.getAsset().getVersion());

		/* an external tileset must match the entry referring to it */

		if (parent != null) {
			assertArrayEquals(parent.getBoundingVolume().getRegion(),
					tileset.getRoot().getBoundingVolume().getRegion(), 1e-12);
			assertEquals(parent.getGeometricError().doubleValue(),
					tileset.getRoot().getGeometricError().doubleValue(), 1e-9);
		}

		walkEntry(tileset.getRoot(), file.getParent(), dir, reachedTiles);

	}

	private static void walkEntry(TilesetEntry entry, Path fileDir, Path dir, Set<TileNumber> reachedTiles)
			throws IOException {

		if (entry.getContent() != null) {

			Path target = fileDir.resolve(entry.getContent().getUri()).normalize();
			assertTrue("content is within the tileset directory: " + target, target.startsWith(dir));

			String relativePath = dir.relativize(target).toString().replace('\\', '/');

			if (relativePath.startsWith("index/")) {
				walk(target, dir, entry, reachedTiles);
			} else {
				/* the per-tile tileset.json, which is written by TilesetOutput rather than by this class */
				var m = Pattern.compile("(\\d+)/(\\d+)/(\\d+)\\.tileset\\.json").matcher(relativePath);
				assertTrue("content path refers to a tile: " + relativePath, m.matches());
				var tile = new TileNumber(Integer.parseInt(m.group(1)),
						Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
				assertRegion(entry, tile);
				assertTrue("each tile is reachable only once: " + tile, reachedTiles.add(tile));
			}

		}

		if (entry.getChildren() != null) {
			for (TilesetEntry child : entry.getChildren()) {

				/* a child must be contained in its parent and refine it */

				double[] parentRegion = entry.getBoundingVolume().getRegion();
				double[] childRegion = child.getBoundingVolume().getRegion();

				assertTrue(childRegion[0] >= parentRegion[0] && childRegion[1] >= parentRegion[1]);
				assertTrue(childRegion[2] <= parentRegion[2] && childRegion[3] <= parentRegion[3]);
				assertTrue(child.getGeometricError().doubleValue() < entry.getGeometricError().doubleValue());

				walkEntry(child, fileDir, dir, reachedTiles);

			}
		}

	}

	/**
	 * asserts that the entry's bounding volume is the union of the bounds of the given tiles' content, in radians.
	 * Assumes the content of each tile has been written with {@link #writeTileTilesets(Path, List)}.
	 */
	private static void assertRegion(TilesetEntry entry, TileNumber... tiles) {

		double[] expected = null;

		for (TileNumber tile : tiles) {
			double[] tileRegion = contentRegion(tile);
			if (expected == null) {
				expected = tileRegion;
			} else {
				expected[0] = Math.min(expected[0], tileRegion[0]);
				expected[1] = Math.min(expected[1], tileRegion[1]);
				expected[2] = Math.max(expected[2], tileRegion[2]);
				expected[3] = Math.max(expected[3], tileRegion[3]);
				expected[4] = Math.min(expected[4], tileRegion[4]);
				expected[5] = Math.max(expected[5], tileRegion[5]);
			}
		}

		assertArrayEquals(expected, entry.getBoundingVolume().getRegion(), 1e-12);

	}

	/**
	 * the bounding volume which {@link #writeTileTilesets(Path, List)} writes for a tile.
	 * The content covers the northern half of the tile, so that it can be told apart from the full tile bounds,
	 * and its height range differs from tile to tile.
	 */
	private static double[] contentRegion(TileNumber tile) {

		LatLonBounds bounds = tile.latLonBounds();
		double midLat = (bounds.minlat + bounds.maxlat) / 2;

		return new double[] {
				toRadians(bounds.minlon),
				toRadians(midLat),
				toRadians(bounds.maxlon),
				toRadians(bounds.maxlat),
				-1,
				10 + tile.y % 7};

	}

	/** writes the tileset.json of each individual tile, as {@link TilesetOutput} would */
	private static void writeTileTilesets(Path dir, List<TileNumber> tiles) throws IOException {

		for (TileNumber tile : tiles) {

			var root = new TilesetParentEntry();
			root.setGeometricError(25);
			root.setBoundingVolume(contentRegion(tile));
			root.setContent(tile.y + ".glb");

			var tileset = new TilesetRoot();
			tileset.setAsset(new TilesetAsset());
			tileset.setGeometricError(25);
			tileset.setRoot(root);

			Path file = dir
					.resolve(Integer.toString(tile.zoom))
					.resolve(Integer.toString(tile.x))
					.resolve(tile.y + ".tileset.json");

			Files.createDirectories(file.getParent());

			try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				JsonUtil.toJson(tileset, writer, false);
			}

		}

	}

	private static TilesetRoot readTileset(Path path) throws IOException {
		assertTrue("tileset file exists: " + path, Files.exists(path));
		try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return JsonUtil.fromJson(reader, TilesetRoot.class);
		}
	}

}
