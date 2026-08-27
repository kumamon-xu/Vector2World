package org.osm2world.buildingtiler.tiles;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.locationtech.jts.geom.Envelope;
import org.osm2world.buildingtiler.domain.BuildingFeature;
import org.osm2world.buildingtiler.modeling.StableStyleHash;
import org.osm2world.math.geo.TileNumber;

public final class TileOwnershipPlanner {

	public static final double WEB_MERCATOR_MAX_LATITUDE = 85.0511287798066;
	public static final Comparator<TileNumber> TILE_ORDER = Comparator
			.comparingInt((TileNumber tile) -> tile.zoom)
			.thenComparingInt(tile -> tile.x)
			.thenComparingInt(tile -> tile.y);

	private final StableStyleHash hashes = new StableStyleHash();

	public TilingPlan plan(List<BuildingFeature> features, int zoom, int largeSpanWarning) {
		if (features == null || features.isEmpty()) throw new IllegalArgumentException("No buildings to tile");
		if (zoom < 0 || zoom > 22) throw new IllegalArgumentException("zoom must be between 0 and 22");
		Map<TileNumber, List<BuildingFeature>> grouped = new TreeMap<>(TILE_ORDER);
		Envelope bounds = new Envelope();
		int crossTile = 0;
		int large = 0;
		List<String> warnings = new ArrayList<>();
		for (BuildingFeature feature : features) {
			Envelope envelope = feature.geometryWgs84().getEnvelopeInternal();
			bounds.expandToInclude(envelope);
			int span = tileSpan(envelope, zoom);
			if (span > 1) crossTile++;
			if (span >= largeSpanWarning) {
				large++;
				warnings.add(feature.id() + " spans " + span + " Z" + zoom
						+ " tiles; assigned once to its centroid owner with the full footprint");
			}
			grouped.computeIfAbsent(owner(feature, zoom), ignored -> new ArrayList<>()).add(feature);
		}

		List<TileWork> work = grouped.entrySet().stream().map(entry -> {
			List<BuildingFeature> ordered = entry.getValue().stream()
					.sorted(Comparator.comparing(hashes::featureKey)).toList();
			return new TileWork(entry.getKey(), ordered);
		}).toList();
		String ownershipCanonical = work.stream()
				.map(tile -> tile.tile().toString() + "=" + tile.features().stream()
						.map(hashes::featureKey).sorted().reduce((a, b) -> a + "," + b).orElse(""))
				.reduce((a, b) -> a + "\n" + b).orElse("");
		return new TilingPlan(work, crossTile, large, bounds,
				StableStyleHash.hash(ownershipCanonical), List.copyOf(warnings));
	}

	public TileNumber owner(BuildingFeature feature, int zoom) {
		var centroid = feature.geometryWgs84().getCentroid().getCoordinate();
		return atWgs84(zoom, centroid.x, centroid.y);
	}

	public TileNumber atWgs84(int zoom, double longitude, double latitude) {
		if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
				|| longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
			throw new IllegalArgumentException("Coordinate outside WGS84 range");
		}
		int scale = 1 << zoom;
		double safeLongitude = longitude == 180 ? Math.nextDown(180.0) : longitude;
		double safeLatitude = Math.max(-WEB_MERCATOR_MAX_LATITUDE,
				Math.min(WEB_MERCATOR_MAX_LATITUDE, latitude));
		int x = Math.min(scale - 1, Math.max(0, (int)Math.floor((safeLongitude + 180.0) / 360.0 * scale)));
		double latitudeRadians = Math.toRadians(safeLatitude);
		int y = (int)Math.floor((1.0 - Math.log(Math.tan(latitudeRadians)
				+ 1.0 / Math.cos(latitudeRadians)) / Math.PI) / 2.0 * scale);
		y = Math.min(scale - 1, Math.max(0, y));
		return new TileNumber(zoom, x, y);
	}

	public int tileSpan(Envelope envelope, int zoom) {
		if (envelope == null || envelope.isNull()) return 0;
		TileNumber southWest = atWgs84(zoom, envelope.getMinX(), envelope.getMinY());
		TileNumber northEast = atWgs84(zoom, envelope.getMaxX(), envelope.getMaxY());
		long columns = Math.abs((long)northEast.x - southWest.x) + 1;
		long rows = Math.abs((long)southWest.y - northEast.y) + 1;
		long count = columns * rows;
		return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)count;
	}

	public record TilingPlan(
			List<TileWork> tiles,
			int crossTileBuildings,
			int largeBuildings,
			Envelope boundsWgs84,
			String ownershipHash,
			List<String> warnings) {
		public TilingPlan {
			tiles = List.copyOf(tiles);
			boundsWgs84 = new Envelope(boundsWgs84);
			warnings = List.copyOf(warnings);
		}
	}
}
