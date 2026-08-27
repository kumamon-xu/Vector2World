package org.osm2world.buildingtiler.tiles;

import java.util.List;

import org.osm2world.buildingtiler.domain.BuildingFeature;
import org.osm2world.math.geo.TileNumber;

public record TileWork(TileNumber tile, List<BuildingFeature> features) {
	public TileWork {
		if (tile == null || features == null || features.isEmpty()) {
			throw new IllegalArgumentException("Tile work requires a tile and at least one feature");
		}
		features = List.copyOf(features);
	}
}
