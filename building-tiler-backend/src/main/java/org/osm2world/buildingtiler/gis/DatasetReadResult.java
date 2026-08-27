package org.osm2world.buildingtiler.gis;

import java.util.List;

import org.osm2world.buildingtiler.domain.BuildingFeature;
import org.osm2world.buildingtiler.domain.DatasetMetadata;

public record DatasetReadResult(List<BuildingFeature> buildings, DatasetMetadata metadata) {

	public DatasetReadResult {
		buildings = List.copyOf(buildings);
	}

}
