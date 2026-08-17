package org.osm2world.output.tileset;

import static java.lang.Math.toRadians;
import static java.util.Collections.emptyList;
import static org.junit.Assert.*;
import static org.osm2world.output.tileset.TilesetTreeUtil.generateTilesetTree;
import static org.osm2world.output.tileset.TilesetTreeUtil.smallestCommonAncestor;
import static org.osm2world.scene.mesh.LevelOfDetail.LOD1;
import static org.osm2world.scene.mesh.LevelOfDetail.LOD3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.junit.Test;
import org.osm2world.math.geo.LatLonBounds;
import org.osm2world.math.geo.TileNumber;
import org.osm2world.output.tileset.tiles_data.TilesetAsset;
import org.osm2world.output.tileset.tiles_data.TilesetEntry;
import org.osm2world.output.tileset.tiles_data.TilesetParentEntry;
import org.osm2world.output.tileset.tiles_data.TilesetRoot;
import org.osm2world.scene.mesh.LevelOfDetail;
import org.osm2world.util.platform.json.JsonImplementationJvm;
import org.osm2world.util.platform.json.JsonUtil;
import org.osm2world.util.test.TestFileUtil;

public class TilesetTreeUtilTest {

	static {
		JsonImplementationJvm.register();
	}

	/** the levels of detail used by most of the tests */
	private static final List<LevelOfDetail> LODS = List.of(LOD1, LOD3);

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
		writeTileTilesets(dir, List.of(tile), LODS);
		generateTilesetTree(dir, List.of(tile), LODS);

		/* the tile is its own smallest common ancestor, so the root refers to its chain of levels of detail */

		TilesetRoot tileset = readTileset(dir.resolve("tileset.json"));

		assertEquals("1.1", tileset.getAsset().getVersion());
		assertEquals(treeGeometricError(0), tileset.getGeometricError().doubleValue(), 1e-9);

		TilesetEntry root = tileset.getRoot();
		assertRegion(root, LODS, tile);
		assertEquals(treeGeometricError(0), root.getGeometricError().doubleValue(), 1e-9);
		assertEquals("index/15/17608/11312.tileset.json", root.getContent().getUri());
		assertNull(root.getChildren());

	}

	@Test
	public void testLodChain() throws IOException {

		var tile = new TileNumber(15, 17608, 11312);

		Path dir = TestFileUtil.createTempDirectory().toPath();
		writeTileTilesets(dir, List.of(tile), LODS);
		generateTilesetTree(dir, List.of(tile), LODS);

		TilesetRoot chain = readTileset(dir.resolve("index/15/17608/11312.tileset.json"));

		/* the chain starts at the lowest level of detail and refines it with the higher ones */

		TilesetParentEntry lod1 = chain.getRoot();
		assertEquals("REPLACE", lod1.getRefine());
		assertEquals(expectedGeometricError(LOD1), lod1.getGeometricError().doubleValue(), 1e-9);
		assertEquals(expectedGeometricError(LOD1), chain.getGeometricError().doubleValue(), 1e-9);
		assertEquals("../../../lod1/15/17608/11312.glb", lod1.getContent().getUri());

		assertEquals(1, lod1.getChildren().size());
		TilesetEntry lod3 = lod1.getChildren().get(0);
		assertEquals("../../../lod3/15/17608/11312.glb", lod3.getContent().getUri());
		assertNull(lod3.getChildren());

		/* the highest level of detail is not refined any further */

		assertEquals(0.0, lod3.getGeometricError().doubleValue(), 1e-9);

		/* every level of detail covers the same area, the union of the areas of all levels of detail */

		assertRegion(lod1, LODS, tile);
		assertRegion(lod3, LODS, tile);

		/* the transform is only set once, because transforms of nested tiles are combined */

		assertNotNull(lod1.getTransform());
		assertNull(lod3.getChildren());
		assertArrayEquals(transformOf(tile), lod1.getTransform(), 1e-9);

	}

	@Test
	public void testLodChainSingleLod() throws IOException {

		var tile = new TileNumber(15, 17608, 11312);
		List<LevelOfDetail> lods = List.of(LOD3);

		Path dir = TestFileUtil.createTempDirectory().toPath();
		writeTileTilesets(dir, List.of(tile), lods);
		generateTilesetTree(dir, List.of(tile), lods);

		TilesetRoot chain = readTileset(dir.resolve("index/15/17608/11312.tileset.json"));

		/* with only one level of detail there is nothing to refine, but the geometric error must not be 0.
		 * A geometric error of 0 would keep the tiles containing this one from ever being refined. */

		assertNull(chain.getRoot().getChildren());
		assertEquals(expectedGeometricError(LOD3), chain.getRoot().getGeometricError().doubleValue(), 1e-9);
		assertTrue(readTileset(dir.resolve("tileset.json")).getGeometricError().doubleValue() > 0);

	}

	@Test
	public void testLodChainIsReachedBeforeItIsRefined() throws IOException {

		var tile = new TileNumber(15, 17608, 11312);

		Path dir = TestFileUtil.createTempDirectory().toPath();
		writeTileTilesets(dir, List.of(tile, new TileNumber(15, 17609, 11312)), LODS);
		generateTilesetTree(dir, List.of(tile, new TileNumber(15, 17609, 11312)), LODS);

		/* A client always traverses into an external tileset, regardless of its geometric error, but it only
		 * refines the tile within it once the error is too large. The lowest level of detail is therefore only
		 * ever shown if its geometric error is smaller than that of the tile of the tree referring to it. */

		TilesetEntry treeEntry = readTileset(dir.resolve("tileset.json")).getRoot().getChildren().get(0);
		TilesetRoot chain = readTileset(dir.resolve("index/15/17608/11312.tileset.json"));

		assertTrue("the lowest level of detail is shown before it is refined",
				chain.getRoot().getGeometricError().doubleValue() < treeEntry.getGeometricError().doubleValue());

		/* the same has to hold for every level of detail, whichever of them a tileset happens to contain */

		for (LevelOfDetail lod : LevelOfDetail.values()) {
			assertTrue("geometric error of " + lod + " is on the scale of levels of detail, not of tree tiles",
					expectedGeometricError(lod) < TilesetTreeUtil.GEOMETRIC_ERROR_AT_MAX_ZOOM);
		}

	}

	@Test
	public void testGenerateTilesetTreePartialLods() throws IOException {

		var tile0 = new TileNumber(15, 17608, 11312);
		var tile1 = new TileNumber(15, 17609, 11312);

		/* the first tile exists at both levels of detail, the second one only at the higher one */

		Path dir = TestFileUtil.createTempDirectory().toPath();
		writeTileTilesets(dir, List.of(tile0), LODS);
		writeTileTilesets(dir, List.of(tile1), List.of(LOD3));
		generateTilesetTree(dir, List.of(tile0, tile1), LODS);

		TilesetRoot chain0 = readTileset(dir.resolve("index/15/17608/11312.tileset.json"));
		assertEquals(expectedGeometricError(LOD1), chain0.getRoot().getGeometricError().doubleValue(), 1e-9);
		assertEquals(1, chain0.getRoot().getChildren().size());

		TilesetRoot chain1 = readTileset(dir.resolve("index/15/17609/11312.tileset.json"));
		assertEquals(expectedGeometricError(LOD3), chain1.getRoot().getGeometricError().doubleValue(), 1e-9);
		assertNull(chain1.getRoot().getChildren());
		assertEquals("../../../lod3/15/17609/11312.glb", chain1.getRoot().getContent().getUri());

		/* both tiles are reachable */

		assertEquals(Set.of("lod1/15/17608/11312.glb", "lod3/15/17608/11312.glb", "lod3/15/17609/11312.glb"),
				walkFromRoot(dir));

	}

	@Test
	public void testGenerateTilesetTreeSingleFile() throws IOException {

		var tile0 = new TileNumber(15, 17608, 11312);
		var tile1 = new TileNumber(15, 17609, 11312);

		Path dir = TestFileUtil.createTempDirectory().toPath();
		writeTileTilesets(dir, List.of(tile0, tile1), LODS);
		generateTilesetTree(dir, List.of(tile0, tile1), LODS);

		/* both tiles share a parent at zoom 14, so the whole tree fits into a single file */

		TilesetRoot tileset = readTileset(dir.resolve("tileset.json"));

		TilesetEntry root = tileset.getRoot();
		assertRegion(root, LODS, tile0, tile1);
		assertNull(root.getContent());

		/* the geometric error doubles with each level towards the root */

		assertEquals(treeGeometricError(1), root.getGeometricError().doubleValue(), 1e-9);

		/* only the two tiles which exist are present, not all four children of the zoom 14 tile */

		assertEquals(2, root.getChildren().size());

		assertEquals(List.of("index/15/17608/11312.tileset.json", "index/15/17609/11312.tileset.json"),
				root.getChildren().stream().map(it -> it.getContent().getUri()).sorted().toList());

		assertRegion(root.getChildren().get(0), LODS, tile0);
		assertRegion(root.getChildren().get(1), LODS, tile1);

	}

	@Test
	public void testGenerateTilesetTreeMultipleFiles() throws IOException {

		var tile0 = new TileNumber(15, 17608, 11312);
		var tile1 = new TileNumber(15, 17620, 11312);

		Path dir = TestFileUtil.createTempDirectory().toPath();
		writeTileTilesets(dir, List.of(tile0, tile1), LODS);
		generateTilesetTree(dir, List.of(tile0, tile1), LODS);

		/* the smallest common ancestor is at zoom 10, i.e. 5 levels above the tiles.
		 * With at most 4 levels per file, two files are needed, so the 5 levels are split 3 + 2. */

		TilesetEntry entry = readTileset(dir.resolve("tileset.json")).getRoot();
		assertRegion(entry, LODS, tile0, tile1);
		assertEquals(treeGeometricError(5), entry.getGeometricError().doubleValue(), 1e-9);

		/* descend the three levels within the root file.
		 * The tiles are in different zoom 11 tiles, so the tree branches immediately below the root. */

		assertEquals(2, entry.getChildren().size());
		entry = entry.getChildren().get(0);

		for (int zoom = 12; zoom <= 13; zoom++) {
			assertNull("no content above the split at zoom 13", entry.getContent());
			assertEquals(1, entry.getChildren().size());
			entry = entry.getChildren().get(0);
		}

		/* the zoom 13 tile refers to the file continuing the tree as an external tileset */

		assertEquals("index/13/4402/2828.tileset.json", entry.getContent().getUri());
		assertNull(entry.getChildren());

		/* the referenced file repeats the tile it is referenced from, and leads to the actual tile */

		TilesetRoot subTileset = readTileset(dir.resolve("index/13/4402/2828.tileset.json"));
		assertEquals(treeGeometricError(2), subTileset.getGeometricError().doubleValue(), 1e-9);

		TilesetEntry leaf = subTileset.getRoot().getChildren().get(0).getChildren().get(0);
		assertRegion(leaf, LODS, tile0);
		assertEquals("../../15/17608/11312.tileset.json", leaf.getContent().getUri());

		/* the second tile is reached through a file of its own */

		assertTrue(Files.exists(dir.resolve("index/13/4405/2828.tileset.json")));

	}

	@Test
	public void testGenerateTilesetTreeDuplicateTiles() throws IOException {

		var tile = new TileNumber(15, 17608, 11312);

		Path dir = TestFileUtil.createTempDirectory().toPath();
		writeTileTilesets(dir, List.of(tile), LODS);
		generateTilesetTree(dir, List.of(tile, tile), LODS);

		assertEquals("index/15/17608/11312.tileset.json",
				readTileset(dir.resolve("tileset.json")).getRoot().getContent().getUri());

	}

	@Test(expected = IllegalArgumentException.class)
	public void testGenerateTilesetTreeNestedTiles() throws IOException {
		List<TileNumber> tiles = List.of(new TileNumber(13, 4402, 2828), new TileNumber(15, 17608, 11312));
		Path dir = TestFileUtil.createTempDirectory().toPath();
		writeTileTilesets(dir, tiles, LODS);
		generateTilesetTree(dir, tiles, LODS);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testGenerateTilesetTreeEmpty() throws IOException {
		Path dir = TestFileUtil.createTempDirectory().toPath();
		generateTilesetTree(dir, emptyList(), LODS);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testGenerateTilesetTreeEmptyLods() throws IOException {
		Path dir = TestFileUtil.createTempDirectory().toPath();
		generateTilesetTree(dir, List.of(new TileNumber(15, 17608, 11312)), emptyList());
	}

	@Test
	public void testGenerateTilesetTreeUsesContentBounds() throws IOException {

		var tile0 = new TileNumber(15, 17608, 11312);
		var tile1 = new TileNumber(15, 17609, 11313);

		Path dir = TestFileUtil.createTempDirectory().toPath();
		writeTileTilesets(dir, List.of(tile0, tile1), LODS);
		generateTilesetTree(dir, List.of(tile0, tile1), LODS);

		TilesetEntry root = readTileset(dir.resolve("tileset.json")).getRoot();
		double[] region = root.getBoundingVolume().getRegion();

		/* the bounds are those of the content, not those of the zoom 14 tile containing both tiles */

		LatLonBounds tileBounds = new TileNumber(14, 8804, 5656).latLonBounds();

		assertTrue("south of the content bounds is north of the south edge of the tile",
				region[1] > toRadians(tileBounds.minlat));
		assertEquals(toRadians(tileBounds.maxlat), region[3], 1e-12);

		/* the height range is the union across tiles and levels of detail, rather than a fixed range */

		assertEquals(-1, region[4], 1e-12);
		assertEquals(Math.max(contentRegion(tile0, LOD3)[5], contentRegion(tile1, LOD3)[5]), region[5], 1e-12);
		assertTrue("height range is tight", region[5] - region[4] < 100);

	}

	@Test
	public void testGenerateTilesetTreeSkipsMissingTiles() throws IOException {

		var tile0 = new TileNumber(15, 17608, 11312);
		var tile1 = new TileNumber(15, 17609, 11312);
		var missingTile = new TileNumber(15, 17609, 11313);

		/* one of the requested tiles does not exist at any level of detail, e.g. because it failed to render */

		Path dir = TestFileUtil.createTempDirectory().toPath();
		writeTileTilesets(dir, List.of(tile0, tile1), LODS);
		generateTilesetTree(dir, List.of(tile0, tile1, missingTile), LODS);

		/* the missing tile is left out of the tree instead of being referenced but unavailable */

		assertEquals(Set.of(
				"lod1/15/17608/11312.glb", "lod3/15/17608/11312.glb",
				"lod1/15/17609/11312.glb", "lod3/15/17609/11312.glb"),
				walkFromRoot(dir));

	}

	@Test(expected = IOException.class)
	public void testGenerateTilesetTreeNoTilesExist() throws IOException {
		Path dir = TestFileUtil.createTempDirectory().toPath();
		generateTilesetTree(dir, List.of(new TileNumber(15, 17608, 11312)), LODS);
	}

	@Test
	public void testGenerateTilesetTreeStructure() throws IOException {

		List<TileNumber> tiles = TileNumber.tilesForBounds(15,
				new LatLonBounds(48.53, 13.38, 48.61, 13.51));

		assertTrue("test covers several tiles", tiles.size() > 20);

		Path dir = TestFileUtil.createTempDirectory().toPath();
		writeTileTilesets(dir, tiles, LODS);
		generateTilesetTree(dir, tiles, LODS);

		/* walking the tree from the entry point must reach the content of every tile at every level of detail
		 * exactly once, and bounding volumes and geometric errors must be consistent along the way */

		Set<String> expected = new HashSet<>();
		for (TileNumber tile : tiles) {
			for (LevelOfDetail lod : LODS) {
				expected.add("lod" + lod.ordinal() + "/" + tile.zoom + "/" + tile.x + "/" + tile.y + ".glb");
			}
		}

		assertEquals(expected, walkFromRoot(dir));

	}

	/**
	 * walks the entire tileset from its entry point, checking its consistency along the way,
	 * and returns the paths of all content files it makes reachable
	 */
	private static Set<String> walkFromRoot(Path dir) throws IOException {
		var reachedContent = new HashSet<String>();
		walk(dir.resolve("tileset.json"), dir, null, reachedContent);
		return reachedContent;
	}

	/** recursively checks a tileset.json and everything it refers to, collecting the content it makes reachable */
	private static void walk(Path file, Path dir, @Nullable TilesetEntry parent, Set<String> reachedContent)
			throws IOException {

		TilesetRoot tileset = readTileset(file);
		assertEquals("1.1", tileset.getAsset().getVersion());
		assertEquals("REPLACE", tileset.getRoot().getRefine());

		/* an external tileset must match the entry referring to it */

		if (parent != null) {
			assertArrayEquals(parent.getBoundingVolume().getRegion(),
					tileset.getRoot().getBoundingVolume().getRegion(), 1e-12);
			assertTrue("geometric error does not increase across an external tileset",
					tileset.getRoot().getGeometricError().doubleValue()
							<= parent.getGeometricError().doubleValue());
		}

		walkEntry(tileset.getRoot(), file.getParent(), dir, reachedContent);

	}

	private static void walkEntry(TilesetEntry entry, Path fileDir, Path dir, Set<String> reachedContent)
			throws IOException {

		if (entry.getContent() != null) {

			Path target = fileDir.resolve(entry.getContent().getUri()).normalize();
			assertTrue("content exists: " + target, Files.isRegularFile(target));
			assertTrue("content is within the tileset directory: " + target, target.startsWith(dir));

			String relativePath = dir.relativize(target).toString().replace('\\', '/');

			if (relativePath.endsWith(".tileset.json")) {
				walk(target, dir, entry, reachedContent);
			} else {
				assertTrue("each content file is reachable only once: " + relativePath,
						reachedContent.add(relativePath));
			}

		}

		if (entry.getChildren() != null) {
			for (TilesetEntry child : entry.getChildren()) {

				/* a child must be contained in its parent and refine it */

				double[] parentRegion = entry.getBoundingVolume().getRegion();
				double[] childRegion = child.getBoundingVolume().getRegion();

				assertTrue(childRegion[0] >= parentRegion[0] && childRegion[1] >= parentRegion[1]);
				assertTrue(childRegion[2] <= parentRegion[2] && childRegion[3] <= parentRegion[3]);
				assertTrue(childRegion[4] >= parentRegion[4] && childRegion[5] <= parentRegion[5]);
				assertTrue("geometric error decreases towards the leaves",
						child.getGeometricError().doubleValue() < entry.getGeometricError().doubleValue());

				walkEntry(child, fileDir, dir, reachedContent);

			}
		}

	}

	/**
	 * asserts that the entry's bounding volume is the union of the bounds of the given tiles' content
	 * across all the given levels of detail, in radians.
	 */
	private static void assertRegion(TilesetEntry entry, List<LevelOfDetail> lods, TileNumber... tiles) {

		double[] expected = null;

		for (TileNumber tile : tiles) {
			for (LevelOfDetail lod : lods) {
				double[] region = contentRegion(tile, lod);
				if (expected == null) {
					expected = region;
				} else {
					expected[0] = Math.min(expected[0], region[0]);
					expected[1] = Math.min(expected[1], region[1]);
					expected[2] = Math.max(expected[2], region[2]);
					expected[3] = Math.max(expected[3], region[3]);
					expected[4] = Math.min(expected[4], region[4]);
					expected[5] = Math.max(expected[5], region[5]);
				}
			}
		}

		assertArrayEquals(expected, entry.getBoundingVolume().getRegion(), 1e-12);

	}

	private static double expectedGeometricError(LevelOfDetail lod) {
		return TilesetTreeUtil.GEOMETRIC_ERROR_BY_LOD[lod.ordinal()];
	}

	/** the geometric error expected for a tile of the tree, the given number of levels above the deepest tiles */
	private static double treeGeometricError(int levelsAboveMaxZoom) {
		return TilesetTreeUtil.GEOMETRIC_ERROR_AT_MAX_ZOOM * (1 << levelsAboveMaxZoom);
	}

	/**
	 * the bounding volume which {@link #writeTileTilesets(Path, List, List)} writes for a tile.
	 * The content covers only part of the tile, so that it can be told apart from the full tile bounds,
	 * and both the covered part and the height range grow with the level of detail.
	 */
	private static double[] contentRegion(TileNumber tile, LevelOfDetail lod) {

		LatLonBounds bounds = tile.latLonBounds();
		double southFraction = 0.5 - 0.1 * lod.ordinal();
		double south = bounds.minlat + (bounds.maxlat - bounds.minlat) * southFraction;

		return new double[] {
				toRadians(bounds.minlon),
				toRadians(south),
				toRadians(bounds.maxlon),
				toRadians(bounds.maxlat),
				-1,
				10 + tile.y % 7 + lod.ordinal()};

	}

	/** the transform which {@link #writeTileTilesets(Path, List, List)} writes for a tile */
	private static double[] transformOf(TileNumber tile) {
		double[] result = new double[16];
		for (int i = 0; i < 16; i++) {
			result[i] = i + tile.x % 3;
		}
		return result;
	}

	/** writes the tileset.json and content of each individual tile, as {@link TilesetOutput} would */
	private static void writeTileTilesets(Path dir, List<TileNumber> tiles, List<LevelOfDetail> lods)
			throws IOException {

		for (TileNumber tile : tiles) {
			for (LevelOfDetail lod : lods) {

				var root = new TilesetParentEntry();
				root.setGeometricError(25);
				root.setBoundingVolume(contentRegion(tile, lod));
				root.setTransform(transformOf(tile));
				root.setContent(tile.y + ".glb");

				var tileset = new TilesetRoot();
				tileset.setAsset(new TilesetAsset());
				tileset.setGeometricError(25);
				tileset.setRoot(root);

				Path tileDir = dir
						.resolve("lod" + lod.ordinal())
						.resolve(Integer.toString(tile.zoom))
						.resolve(Integer.toString(tile.x));

				Files.createDirectories(tileDir);

				try (var writer = Files.newBufferedWriter(tileDir.resolve(tile.y + ".tileset.json"),
						StandardCharsets.UTF_8)) {
					JsonUtil.toJson(tileset, writer, false);
				}

				Files.write(tileDir.resolve(tile.y + ".glb"), new byte[] {'g', 'l', 'T', 'F'});

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
