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
import org.osm2world.output.tileset.tiles_data.TilesetEntry;
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
		generateTilesetTree(dir, List.of(tile));

		/* the tile is its own smallest common ancestor, so the root refers to it directly */

		TilesetRoot tileset = readTileset(dir.resolve("tileset.json"));

		assertEquals("1.1", tileset.getAsset().getVersion());
		assertEquals(25.0, tileset.getGeometricError().doubleValue(), 1e-9);

		TilesetEntry root = tileset.getRoot();
		assertRegionOf(tile, root);
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
		generateTilesetTree(dir, List.of(tile0, tile1));

		/* both tiles share a parent at zoom 14, so the whole tree fits into a single file */

		assertFalse(Files.exists(dir.resolve("index")));

		TilesetRoot tileset = readTileset(dir.resolve("tileset.json"));

		TilesetEntry root = tileset.getRoot();
		assertRegionOf(new TileNumber(14, 8804, 5656), root);
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

		assertRegionOf(tile0, root.getChildren().get(0));
		assertRegionOf(tile1, root.getChildren().get(1));

	}

	@Test
	public void testGenerateTilesetTreeMultipleFiles() throws IOException {

		var tile0 = new TileNumber(15, 17608, 11312);
		var tile1 = new TileNumber(15, 17620, 11312);

		Path dir = TestFileUtil.createTempDirectory().toPath();
		generateTilesetTree(dir, List.of(tile0, tile1));

		/* the smallest common ancestor is at zoom 10, i.e. 5 levels above the tiles.
		 * With at most 4 levels per file, two files are needed, so the 5 levels are split 3 + 2. */

		TilesetRoot tileset = readTileset(dir.resolve("tileset.json"));

		TilesetEntry entry = tileset.getRoot();
		assertRegionOf(new TileNumber(10, 550, 353), entry);
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
		assertRegionOf(splitTile, entry);
		assertEquals("index/13/4402/2828.tileset.json", entry.getContent().getUri());
		assertNull(entry.getChildren());

		/* the referenced file repeats the tile it is referenced from, and leads to the actual tile */

		TilesetRoot subTileset = readTileset(dir.resolve("index/13/4402/2828.tileset.json"));

		TilesetEntry subRoot = subTileset.getRoot();
		assertRegionOf(splitTile, subRoot);
		assertEquals(100.0, subRoot.getGeometricError().doubleValue(), 1e-9);
		assertEquals(100.0, subTileset.getGeometricError().doubleValue(), 1e-9);

		assertEquals(1, subRoot.getChildren().size());
		TilesetEntry leaf = subRoot.getChildren().get(0).getChildren().get(0);
		assertRegionOf(tile0, leaf);
		assertEquals("../../../15/17608/11312.tileset.json", leaf.getContent().getUri());

		/* the second tile is reached through a file of its own */

		assertTrue(Files.exists(dir.resolve("index/13/4405/2828.tileset.json")));

	}

	@Test
	public void testGenerateTilesetTreeDuplicateTiles() throws IOException {

		var tile = new TileNumber(15, 17608, 11312);

		Path dir = TestFileUtil.createTempDirectory().toPath();
		generateTilesetTree(dir, List.of(tile, tile));

		assertEquals("15/17608/11312.tileset.json",
				readTileset(dir.resolve("tileset.json")).getRoot().getContent().getUri());

	}

	@Test(expected = IllegalArgumentException.class)
	public void testGenerateTilesetTreeNestedTiles() throws IOException {
		Path dir = TestFileUtil.createTempDirectory().toPath();
		generateTilesetTree(dir, List.of(new TileNumber(13, 4402, 2828), new TileNumber(15, 17608, 11312)));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testGenerateTilesetTreeEmpty() throws IOException {
		Path dir = TestFileUtil.createTempDirectory().toPath();
		generateTilesetTree(dir, emptyList());
	}

	@Test
	public void testGenerateTilesetTreeStructure() throws IOException {

		List<TileNumber> tiles = TileNumber.tilesForBounds(15,
				new LatLonBounds(48.53, 13.38, 48.61, 13.51));

		assertTrue("test covers several tiles", tiles.size() > 20);

		Path dir = TestFileUtil.createTempDirectory().toPath();
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
				assertRegionOf(tile, entry);
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

	/** asserts that the entry's bounding volume is the region covered by the tile, in radians */
	private static void assertRegionOf(TileNumber tile, TilesetEntry entry) {

		LatLonBounds bounds = tile.latLonBounds();
		double[] region = entry.getBoundingVolume().getRegion();

		assertEquals(toRadians(bounds.minlon), region[0], 1e-12);
		assertEquals(toRadians(bounds.minlat), region[1], 1e-12);
		assertEquals(toRadians(bounds.maxlon), region[2], 1e-12);
		assertEquals(toRadians(bounds.maxlat), region[3], 1e-12);
		assertTrue(region[4] < region[5]);

	}

	private static TilesetRoot readTileset(Path path) throws IOException {
		assertTrue("tileset file exists: " + path, Files.exists(path));
		try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return JsonUtil.fromJson(reader, TilesetRoot.class);
		}
	}

}
