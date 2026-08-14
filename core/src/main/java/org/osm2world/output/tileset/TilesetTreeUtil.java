package org.osm2world.output.tileset;

import static java.lang.Math.max;
import static java.lang.Math.pow;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.osm2world.math.geo.LatLonBounds;
import org.osm2world.math.geo.TileNumber;
import org.osm2world.output.tileset.tiles_data.TilesetAsset;
import org.osm2world.output.tileset.tiles_data.TilesetEntry;
import org.osm2world.output.tileset.tiles_data.TilesetParentEntry;
import org.osm2world.output.tileset.tiles_data.TilesetRoot;
import org.osm2world.util.platform.json.JsonUtil;

/**
 * Utility class for generating the tree of tileset.json files which ties the individual tiles of a tileset together,
 * according to the Cesium 3D Tiles specification.
 * The tiles themselves are not created by this class, use {@link TilesetOutput} for that.
 *
 * <p>The tree consists of explicit tiles all the way down: every tile of the pyramid is described by its own entry,
 * with the exact bounds of the {@link TileNumber} it represents. Implicit tiling is deliberately not used because it
 * derives the bounds of descendant tiles by subdividing the root's bounding volume linearly. For a {@code region}
 * bounding volume that subdivision is linear in latitude, whereas tile numbers follow Web Mercator.
 *
 * <p>To keep individual files small, the tree is split across several tileset.json files, each spanning at most
 * {@link #MAX_LEVELS_PER_FILE} levels. A tile at the bottom of such a file refers to the file continuing below it as
 * an external tileset. The bottom-most tiles refer to the per-tile tileset.json written by {@link TilesetOutput}.
 */
public final class TilesetTreeUtil {

	/**
	 * maximum number of levels of subdivision described by a single tileset.json file.
	 * The levels are distributed evenly among the necessary number of files, so files may span fewer levels than this.
	 */
	private static final int MAX_LEVELS_PER_FILE = 4;

	/**
	 * geometric error assigned to the tiles containing the actual content.
	 * Matches the geometric error which {@link TilesetOutput} assigns to the root of a per-tile tileset.
	 * The geometric error doubles with each level towards the root.
	 */
	private static final double LEAF_GEOMETRIC_ERROR = 25;

	/** elevation range (in meters) used for the bounding volumes of the tiles in the tree */
	private static final double MIN_HEIGHT = -100, MAX_HEIGHT = 9000;

	/** name of the tileset.json at the top of the tree, the entry point for clients */
	private static final String ROOT_FILE_NAME = "tileset.json";

	/** subdirectory for the tileset.json files below the root, keeps them separate from the per-tile tilesets */
	private static final String INTERMEDIATE_DIR_NAME = "index";

	private TilesetTreeUtil() {}

	/**
	 * generates the tileset.json files which make the tiles with the given tile numbers reachable from a single
	 * entry point at {@code <tilesetDir>/tileset.json}.
	 *
	 * @param tilesetDir  directory containing the tiles, with the tileset.json of each individual tile at
	 *                    {@code <tilesetDir>/<zoom>/<x>/<y>.tileset.json}. Created if it does not exist.
	 * @param tileNumbers  the tiles which exist in this tileset, must not be empty.
	 *                     No tile may be an ancestor of another tile in this list.
	 * @throws IllegalArgumentException  if the list of tile numbers is empty or contains a tile
	 *                                   which is an ancestor of another tile in the list
	 */
	public static void generateTilesetTree(Path tilesetDir, List<TileNumber> tileNumbers) throws IOException {
		new Generator(tilesetDir, tileNumbers).run();
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

	/** holds the state for a single run of {@link #generateTilesetTree(Path, List)} */
	private static final class Generator {

		private final Path tilesetDir;

		/** the tiles with content, i.e. the tiles the tree is built for */
		private final Set<TileNumber> contentTiles;

		/** the tiles making up the tree: the content tiles and all of their ancestors down to (and including) {@link #rootTile} */
		private final Set<TileNumber> treeTiles;

		private final TileNumber rootTile;
		private final int maxZoom;

		/** number of levels described by each tileset.json file, at most {@link #MAX_LEVELS_PER_FILE} */
		private final int levelsPerFile;

		Generator(Path tilesetDir, List<TileNumber> tileNumbers) {

			this.tilesetDir = tilesetDir.toAbsolutePath().normalize();
			this.contentTiles = Set.copyOf(tileNumbers);
			this.rootTile = smallestCommonAncestor(tileNumbers);
			this.maxZoom = tileNumbers.stream().mapToInt(it -> it.zoom).max().getAsInt();

			/* spread the levels evenly across as few files as possible.
			 * Filling up every file except the last one would put the split close to the bottom of the tree,
			 * where it produces a large number of files which each describe only very few tiles. */

			int totalLevels = maxZoom - rootTile.zoom;
			int numFiles = max(1, ceilDiv(totalLevels, MAX_LEVELS_PER_FILE));
			this.levelsPerFile = max(1, ceilDiv(totalLevels, numFiles));

			/* collect the content tiles along with all of their ancestors */

			this.treeTiles = new HashSet<>();

			for (TileNumber tile : contentTiles) {
				for (int zoom = tile.zoom; zoom >= rootTile.zoom; zoom--) {
					if (!treeTiles.add(tile.ancestor(zoom))) {
						break; // this tile, and therefore all of its ancestors, has already been added
					}
				}
			}

			/* a tile with content is a leaf of the tree, so it must not contain another tile with content */

			for (TileNumber tile : contentTiles) {
				for (int zoom = rootTile.zoom; zoom < tile.zoom; zoom++) {
					if (contentTiles.contains(tile.ancestor(zoom))) {
						throw new IllegalArgumentException("tile " + tile.ancestor(zoom)
								+ " is an ancestor of tile " + tile);
					}
				}
			}

		}

		void run() throws IOException {
			writeTilesetFile(tilesetDir.resolve(ROOT_FILE_NAME), rootTile);
		}

		/** writes a tileset.json describing the tile and up to {@link #levelsPerFile} levels of its descendants */
		private void writeTilesetFile(Path file, TileNumber tile) throws IOException {

			var rootEntry = new TilesetParentEntry();
			fillEntry(rootEntry, tile, levelsPerFile, file.getParent());

			var tileset = new TilesetRoot();
			tileset.setAsset(new TilesetAsset());
			tileset.setGeometricError(geometricError(tile.zoom));
			tileset.setRoot(rootEntry);

			Files.createDirectories(file.getParent());

			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				JsonUtil.toJson(tileset, writer, false);
			}

		}

		/**
		 * fills in an entry describing a tile, recursively adding its descendants as children.
		 * Descendants more than {@code remainingLevels} below the tile are placed in a separate tileset.json,
		 * which is written by this method as well.
		 *
		 * @param fileDir  directory of the tileset.json this entry will be written to, used for relative URIs
		 */
		private void fillEntry(TilesetEntry entry, TileNumber tile, int remainingLevels, Path fileDir)
				throws IOException {

			entry.setGeometricError(geometricError(tile.zoom));
			entry.setBoundingVolume(regionOf(tile));

			if (contentTiles.contains(tile)) {

				/* refer to the tileset.json written for this tile by TilesetOutput */

				entry.setContent(relativeUri(fileDir, tileFile(tile)));

			} else if (remainingLevels == 0) {

				/* continue the tree in a separate file, and refer to that file as an external tileset */

				Path childFile = intermediateFile(tile);
				writeTilesetFile(childFile, tile);
				entry.setContent(relativeUri(fileDir, childFile));

			} else {

				for (TileNumber child : childrenOf(tile)) {
					var childEntry = new TilesetEntry();
					fillEntry(childEntry, child, remainingLevels - 1, fileDir);
					entry.addChild(childEntry);
				}

			}

		}

		/** returns those of the tile's four children which are part of the tree, in a deterministic order */
		private List<TileNumber> childrenOf(TileNumber tile) {

			if (tile.zoom >= maxZoom) return List.of();

			List<TileNumber> result = new ArrayList<>(4);

			for (TileNumber child : tile.children()) {
				if (treeTiles.contains(child)) {
					result.add(child);
				}
			}

			return result;

		}

		/** the geometric error at a zoom level, doubling with each level towards the root */
		private double geometricError(int zoom) {
			return LEAF_GEOMETRIC_ERROR * pow(2, maxZoom - zoom);
		}

		/** path of the tileset.json written for an individual tile by {@link TilesetOutput} */
		private Path tileFile(TileNumber tile) {
			return tilesetDir
					.resolve(Integer.toString(tile.zoom))
					.resolve(Integer.toString(tile.x))
					.resolve(tile.y + ".tileset.json");
		}

		/** path of a tileset.json continuing the tree below the given tile */
		private Path intermediateFile(TileNumber tile) {
			return tilesetDir
					.resolve(INTERMEDIATE_DIR_NAME)
					.resolve(Integer.toString(tile.zoom))
					.resolve(Integer.toString(tile.x))
					.resolve(tile.y + ".tileset.json");
		}

	}

	/** integer division rounding towards positive infinity, for non-negative arguments */
	private static int ceilDiv(int x, int y) {
		return (x + y - 1) / y;
	}

	private static TilesetEntry.Region regionOf(TileNumber tile) {
		LatLonBounds bounds = tile.latLonBounds();
		return new TilesetEntry.Region(bounds.getMin(), bounds.getMax(), MIN_HEIGHT, MAX_HEIGHT);
	}

	/** builds a URI for a file, relative to the directory of the tileset.json referring to it */
	private static String relativeUri(Path fileDir, Path target) {
		return fileDir.relativize(target).toString().replace('\\', '/');
	}

}
