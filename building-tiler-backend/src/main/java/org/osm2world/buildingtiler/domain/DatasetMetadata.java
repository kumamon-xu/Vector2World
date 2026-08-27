package org.osm2world.buildingtiler.domain;

import java.nio.file.Path;
import java.util.Map;

import org.locationtech.jts.geom.Envelope;

public record DatasetMetadata(
		Path input,
		String format,
		String sourceCrs,
		String sourceEncoding,
		long featureCount,
		long validBuildings,
		long skippedInvalidHeight,
		long skippedInvalidGeometry,
		Envelope boundsWgs84,
		Map<String, Long> geometryTypes,
		double minHeightMeters,
		double maxHeightMeters) {
}
