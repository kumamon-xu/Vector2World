package org.osm2world.output.tileset;

import static java.lang.Math.max;
import static java.lang.Math.min;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.osm2world.math.geo.TileNumber;
import org.osm2world.output.tileset.tiles_data.TilesetAsset;
import org.osm2world.output.tileset.tiles_data.TilesetEntry;
import org.osm2world.output.tileset.tiles_data.TilesetParentEntry;
import org.osm2world.output.tileset.tiles_data.TilesetRoot;
import org.osm2world.scene.mesh.LevelOfDetail;
import org.osm2world.util.platform.json.JsonUtil;

/**
 * Utility class for generating the tileset.json files which tie the individual tiles of a tileset together,
 * according to the Cesium 3D Tiles specification.
 * The tiles themselves are not created by this class, use {@link TilesetOutput} for that.
 *
 * <p>Two kinds of files are generated, both of them in the {@value #INDEX_DIR_NAME} directory:
 *
 * <ul>
 * <li>For each tile, a file describing the {@link LevelOfDetail versions} of that tile which exist.
 *     They form a chain of tiles which all cover the same area, with each one refining the one above it.</li>
 * <li>The tree tying those tiles together, with one file per {@link #MAX_LEVELS_PER_FILE} levels of the
 *     tile pyramid. The entry point for clients is the {@value #ROOT_FILE_NAME} at the top of the tree.</li>
 * </ul>
 *
 * <p>Both kinds of file use explicit tiles rather than implicit tiling, which derives the bounds of descendant
 * tiles by subdividing the root's bounding volume linearly. For a {@code region} bounding volume that subdivision
 * is linear in latitude, whereas tile numbers follow Web Mercator. Explicit tiles also allow the bounds to be taken
 * from the actual content of each tile, in particular the minimum and maximum elevation.
 *
 * <p>Because the bounds are read from the individual tiles, the tiles need to exist before this class is used.
 */
public final class TilesetTreeUtil {

	/** name of the tileset.json at the top of the tree, the entry point for clients */
	public static final String ROOT_FILE_NAME = "tileset.json";

	/** subdirectory for the generated tileset.json files, keeps them separate from the tiles themselves */
	public static final String INDEX_DIR_NAME = "index";

	/**
	 * geometric error (in meters) of a tile of the tree at the highest zoom level,
	 * doubling with each level towards the root.
	 *
	 * <p>The tiles of the tree have no content of their own, so this value does not describe an actual geometric
	 * deviation. Instead, it controls the distance from which the tiles of the tileset are loaded.
	 */
	static final double GEOMETRIC_ERROR_AT_MAX_ZOOM = 100;

	/**
	 * the geometric error (in meters) of each {@link LevelOfDetail}, indexed by its ordinal.
	 * This controls the distance at which a client switches to the next level of detail.
	 * They all need to be smaller than {@link #GEOMETRIC_ERROR_AT_MAX_ZOOM}:
	 * a tile always needs a larger geometric error than the tiles below it.
	 */
	static final double[] GEOMETRIC_ERROR_BY_LOD = {32, 16, 8, 4, 2};

	/**
	 * maximum number of levels of subdivision described by a single tileset.json file.
	 * The levels are distributed evenly among the necessary number of files, so files may span fewer levels than this.
	 */
	private static final int MAX_LEVELS_PER_FILE = 4;

	private TilesetTreeUtil() {}

	/**
	 * generates the tileset.json files which make the given tiles reachable from the single entry point at
	 * {@link #ROOT_FILE_NAME}.
	 * The tiles themselves must already have been written; any tile which does not exist at any of the levels of
	 * detail is skipped.
	 *
	 * @param baseDir  directory containing the tiles, with the tileset.json of an individual tile at
	 *                 {@code <baseDir>/lod<n>/<zoom>/<x>/<y>.tileset.json}
	 * @param tileNumbers  the tiles which should be part of this tileset, must not be empty.
	 *                     No tile may be an ancestor of another tile in this list.
	 * @param lods  the levels of detail the tiles may exist at, must not be empty
	 * @throws IllegalArgumentException  if the list of tile numbers or levels of detail is empty,
	 *                                   or if a tile is an ancestor of another tile in the list
	 * @throws IOException  if none of the tiles exists, or if reading or writing a tileset.json fails
	 */
	public static void generateTilesetTree(Path baseDir, List<TileNumber> tileNumbers, List<LevelOfDetail> lods)
			throws IOException {
		new Generator(baseDir, tileNumbers, lods).run();
	}

	/**
	 * returns the expected path of a glb or tileset.json for a particular {@link TileNumber} and {@link LevelOfDetail}.
	 */
	public static Path tilePath(Path baseDir, TileNumber tile, LevelOfDetail lod, String extension) {
		return baseDir
				.resolve("lod" + lod.ordinal())
				.resolve(Integer.toString(tile.zoom))
				.resolve(Integer.toString(tile.x))
				.resolve(tile.y + extension);
	}

	/**
	 * returns the tile number of the smallest tile which contains all provided tileNumbers in its bounds
	 *
	 * @param tileNumbers  the tiles to find a common ancestor for, must not be empty
	 * @throws IllegalArgumentException  if the list of tile numbers is empty
	 */
	static TileNumber smallestCommonAncestor(List<TileNumber> tileNumbers) {

		if (tileNumbers.isEmpty()) {
			throw new IllegalArgumentException("at least one tile number is required");
		}

		/* start at the lowest zoom level any of the tiles exists at */

		int zoom = tileNumbers.stream().mapToInt(it -> it.zoom).min().getAsInt();

		TileNumber result = tileNumbers.get(0).ancestor(zoom);

		/* zoom out further until all tiles share the same ancestor */

		for (TileNumber tileNumber : tileNumbers) {
			TileNumber other = tileNumber.ancestor(zoom);
			while (!result.equals(other)) {
				zoom -= 1;
				result = result.ancestor(zoom);
				other = other.ancestor(zoom);
			}
		}

		return result;

	}

	/** one level of detail of one tile, as described by the tileset.json written for it by {@link TilesetOutput} */
	private record LodContent(LevelOfDetail lod, double[] region, double[] transform, Path contentFile) {}

	/** holds the state for a single run of {@link #generateTilesetTree(Path, List, List)} */
	private static final class Generator {

		private final Path baseDir;

		/** the levels of detail of each tile which has at least one of them, ordered from lowest to highest */
		private final Map<TileNumber, List<LodContent>> contentTiles;

		/**
		 * bounding volume of each tile making up the tree, i.e. of the content tiles and of all their ancestors
		 * down to (and including) {@link #rootTile}. The bounds of a content tile are the union of the bounds of
		 * its levels of detail, the bounds of any other tile are the union of the bounds of the tiles below it.
		 */
		private final Map<TileNumber, double[]> regions;

		private final TileNumber rootTile;
		private final int maxZoom;

		/** number of levels described by each tileset.json file, at most {@link #MAX_LEVELS_PER_FILE} */
		private final int levelsPerFile;

		Generator(Path baseDir, List<TileNumber> tileNumbers, List<LevelOfDetail> lods) throws IOException {

			if (tileNumbers.isEmpty()) {
				throw new IllegalArgumentException("at least one tile number is required");
			} else if (lods.isEmpty()) {
				throw new IllegalArgumentException("at least one level of detail is required");
			}

			this.baseDir = baseDir.toAbsolutePath().normalize();

			/* find the levels of detail which exist for each tile.
			 * Tiles or levels of detail may be missing because they failed to render or were not requested. */

			List<LevelOfDetail> sortedLods = lods.stream().distinct().sorted().toList();

			this.contentTiles = new HashMap<>();

			for (TileNumber tile : new HashSet<>(tileNumbers)) {

				List<LodContent> contents = new ArrayList<>();

				for (LevelOfDetail lod : sortedLods) {
					if (Files.isRegularFile(tilePath(baseDir, tile, lod, ".tileset.json"))) {
						contents.add(readLodContent(tile, lod));
					}
				}

				if (!contents.isEmpty()) {
					contentTiles.put(tile, contents);
				}

			}

			if (contentTiles.isEmpty()) {
				throw new IOException("none of the " + tileNumbers.size()
						+ " tiles has a tileset.json in " + this.baseDir);
			}

			/* a tile with content is a leaf of the tree, so it must not contain another tile with content */

			for (TileNumber tile : contentTiles.keySet()) {
				for (int zoom = tile.zoom - 1; zoom >= 0; zoom--) {
					if (contentTiles.containsKey(tile.ancestor(zoom))) {
						throw new IllegalArgumentException("tile " + tile.ancestor(zoom)
								+ " is an ancestor of tile " + tile);
					}
				}
			}

			this.rootTile = smallestCommonAncestor(List.copyOf(contentTiles.keySet()));
			this.maxZoom = contentTiles.keySet().stream().mapToInt(it -> it.zoom).max().getAsInt();

			/* spread the levels evenly across as few files as possible.
			 * Filling up every file except the last one would put the split close to the bottom of the tree,
			 * where it produces a large number of files which each describe only very few tiles. */

			int totalLevels = maxZoom - rootTile.zoom;
			int numFiles = max(1, ceilDiv(totalLevels, MAX_LEVELS_PER_FILE));
			this.levelsPerFile = max(1, ceilDiv(totalLevels, numFiles));

			/* propagate the bounds of each content tile up to the root.
			 * Using the actual bounds of the content, rather than the bounds of the entire tile, keeps the
			 * bounding volumes tight. This matters because clients derive the screen space error from the
			 * distance to the bounding volume, and a tile appears closer than it is if its bounds are too large. */

			this.regions = new HashMap<>();

			contentTiles.forEach((tile, contents) -> {

				double[] region = unionRegion(contents);

				for (int zoom = tile.zoom; zoom >= rootTile.zoom; zoom--) {
					double[] existingRegion = regions.get(tile.ancestor(zoom));
					if (existingRegion == null) {
						regions.put(tile.ancestor(zoom), region.clone());
					} else {
						expandRegion(existingRegion, region);
					}
				}

			});

		}

		/**
		 * the geometric error of a tile of the tree, doubling with each level towards the root.
		 *
		 * <p>This is deliberately unrelated to the geometric error of the levels of detail within a tile.
		 * A tile of the tree is only a container for the tiles below it, and clients traverse into it based on
		 * this value, whereas the levels of detail are actual representations of the same content.
		 */
		private double treeGeometricError(int zoom) {
			return GEOMETRIC_ERROR_AT_MAX_ZOOM * (1 << (maxZoom - zoom));
		}

		void run() throws IOException {

			for (TileNumber tile : contentTiles.keySet()) {
				writeLodChainFile(tile);
			}

			writeTreeFile(baseDir.resolve(ROOT_FILE_NAME), rootTile);

		}

		/** reads the tileset.json which {@link TilesetOutput} has written for one level of detail of a tile */
		private LodContent readLodContent(TileNumber tile, LevelOfDetail lod) throws IOException {

			Path file = tilePath(baseDir, tile, lod, ".tileset.json");

			TilesetRoot tileset;
			try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				tileset = JsonUtil.fromJson(reader, TilesetRoot.class);
			}

			if (tileset == null || tileset.getRoot() == null) {
				throw new IOException("tileset for tile " + tile + " at " + lod + " has no root tile: " + file);
			}

			TilesetParentEntry root = tileset.getRoot();

			if (root.getBoundingVolume() == null || root.getBoundingVolume().getRegion().length != 6) {
				throw new IOException("tileset for tile " + tile + " at " + lod
						+ " has no valid bounding volume: " + file);
			} else if (root.getContent() == null) {
				throw new IOException("tileset for tile " + tile + " at " + lod + " has no content: " + file);
			}

			Path contentFile = file.getParent().resolve(root.getContent().getUri()).normalize();

			return new LodContent(lod, root.getBoundingVolume().getRegion(), root.getTransform(), contentFile);

		}

		/**
		 * writes the tileset.json describing all levels of detail of a single tile.
		 * They form a chain of tiles, each of them covering the same area as, but refining, the one above it.
		 */
		private void writeLodChainFile(TileNumber tile) throws IOException {

			List<LodContent> contents = contentTiles.get(tile);

			Path file = indexFile(tile);
			Path fileDir = file.getParent();

			var rootEntry = new TilesetParentEntry();

			/* the transform is only set on the root because transforms are inherited and combined with each other */

			rootEntry.setTransform(contents.get(0).transform());

			TilesetEntry entry = rootEntry;

			for (int i = 0; i < contents.size(); i++) {

				if (i > 0) {
					var childEntry = new TilesetEntry();
					entry.addChild(childEntry);
					entry = childEntry;
				}

				LodContent content = contents.get(i);

				entry.setGeometricError(geometricErrorInChain(contents, i));
				entry.setBoundingVolume(new TilesetEntry.Region(regions.get(tile).clone()));
				entry.setContent(relativeUri(fileDir, content.contentFile()));

			}

			writeTileset(file, rootEntry, geometricErrorInChain(contents, 0));

		}

		/** writes a tileset.json describing the tile and up to {@link #levelsPerFile} levels of its descendants */
		private void writeTreeFile(Path file, TileNumber tile) throws IOException {

			var rootEntry = new TilesetParentEntry();
			fillTreeEntry(rootEntry, tile, levelsPerFile, file.getParent());

			writeTileset(file, rootEntry, treeGeometricError(tile.zoom));

		}

		/**
		 * fills in an entry describing a tile of the tree, recursively adding its descendants as children.
		 * Descendants more than {@code remainingLevels} below the tile are placed in a separate tileset.json,
		 * which is written by this method as well.
		 *
		 * @param fileDir  directory of the tileset.json this entry will be written to, used for relative URIs
		 */
		private void fillTreeEntry(TilesetEntry entry, TileNumber tile, int remainingLevels, Path fileDir)
				throws IOException {

			entry.setGeometricError(treeGeometricError(tile.zoom));
			entry.setBoundingVolume(new TilesetEntry.Region(regions.get(tile).clone()));

			if (contentTiles.containsKey(tile)) {

				/* refer to the chain of levels of detail for this tile */

				entry.setContent(relativeUri(fileDir, indexFile(tile)));

			} else if (remainingLevels == 0) {

				/* continue the tree in a separate file, and refer to that file as an external tileset */

				Path childFile = indexFile(tile);
				writeTreeFile(childFile, tile);
				entry.setContent(relativeUri(fileDir, childFile));

			} else {

				for (TileNumber child : childrenOf(tile)) {
					var childEntry = new TilesetEntry();
					fillTreeEntry(childEntry, child, remainingLevels - 1, fileDir);
					entry.addChild(childEntry);
				}

			}

		}

		private void writeTileset(Path file, TilesetParentEntry rootEntry, double geometricError) throws IOException {

			var tileset = new TilesetRoot();
			tileset.setAsset(new TilesetAsset());
			tileset.setGeometricError(geometricError);
			tileset.setRoot(rootEntry);

			Files.createDirectories(file.getParent());

			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				JsonUtil.toJson(tileset, writer, false);
			}

		}

		/** returns those of the tile's four children which are part of the tree, in a deterministic order */
		private List<TileNumber> childrenOf(TileNumber tile) {

			if (tile.zoom >= maxZoom) return List.of();

			List<TileNumber> result = new ArrayList<>(4);

			for (TileNumber child : tile.children()) {
				if (regions.containsKey(child)) {
					result.add(child);
				}
			}

			return result;

		}

		/**
		 * path of a tileset.json generated by this class for a tile.
		 * Depending on the tile, this either describes its levels of detail or continues the tree below it.
		 * A tile is never both, because a tile with content is a leaf of the tree.
		 */
		private Path indexFile(TileNumber tile) {
			return baseDir
					.resolve(INDEX_DIR_NAME)
					.resolve(Integer.toString(tile.zoom))
					.resolve(Integer.toString(tile.x))
					.resolve(tile.y + ".tileset.json");
		}

	}

	/**
	 * returns the geometric error for one element of a chain of levels of detail.
	 *
	 * @param index  position in the chain, 0 is the lowest level of detail
	 */
	private static double geometricErrorInChain(List<LodContent> contents, int index) {
		if (index == contents.size() - 1 && contents.size() > 1) {
			/* the highest level of detail is not refined any further.
			 * This is only used if there is something coarser above it, as an error of 0 anywhere
			 * in a tileset would keep the tiles containing it from ever being refined. */
			return 0;
		} else {
			return GEOMETRIC_ERROR_BY_LOD[contents.get(index).lod().ordinal()];
		}
	}

	/** returns the smallest region containing the regions of all the given levels of detail */
	private static double[] unionRegion(List<LodContent> contents) {
		double[] result = contents.get(0).region().clone();
		for (LodContent content : contents) {
			expandRegion(result, content.region());
		}
		return result;
	}

	/** integer division rounding towards positive infinity, for non-negative arguments */
	private static int ceilDiv(int x, int y) {
		return (x + y - 1) / y;
	}

	/** grows a region (in the format used by 3D Tiles) to also contain another region */
	private static void expandRegion(double[] region, double[] otherRegion) {
		region[0] = min(region[0], otherRegion[0]);
		region[1] = min(region[1], otherRegion[1]);
		region[2] = max(region[2], otherRegion[2]);
		region[3] = max(region[3], otherRegion[3]);
		region[4] = min(region[4], otherRegion[4]);
		region[5] = max(region[5], otherRegion[5]);
	}

	/** builds a URI for a file, relative to the directory of the tileset.json referring to it */
	private static String relativeUri(Path fileDir, Path target) {
		return fileDir.relativize(target).toString().replace('\\', '/');
	}

}
