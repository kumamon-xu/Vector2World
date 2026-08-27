package org.osm2world.buildingtiler.domain;

import java.nio.file.Path;
import java.util.List;
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
		double maxHeightMeters,
		String schemaVersion,
		long validGeometryCount,
		long repairedGeometryCount,
		List<FieldMetadata> fields,
		List<HeightCandidate> heightCandidates,
		HeightQualityStatistics heightQuality,
		List<ImportIssue> issues) {

	public static final String SCHEMA_VERSION = "1.0";

	public DatasetMetadata(Path input, String format, String sourceCrs, String sourceEncoding,
			long featureCount, long validBuildings, long skippedInvalidHeight,
			long skippedInvalidGeometry, Envelope boundsWgs84, Map<String, Long> geometryTypes,
			double minHeightMeters, double maxHeightMeters) {
		this(input, format, sourceCrs, sourceEncoding, featureCount, validBuildings,
				skippedInvalidHeight, skippedInvalidGeometry, boundsWgs84, geometryTypes,
				minHeightMeters, maxHeightMeters, SCHEMA_VERSION,
				validBuildings + skippedInvalidHeight, 0, List.of(), List.of(),
				validBuildings == 0 ? HeightQualityStatistics.empty()
						: new HeightQualityStatistics(validBuildings, skippedInvalidHeight, 0, 0, 0, 0,
								minHeightMeters, maxHeightMeters, null),
				List.of());
	}

	public DatasetMetadata {
		if (format == null || format.isBlank()) throw new IllegalArgumentException("Dataset format is required");
		if (sourceCrs == null || sourceCrs.isBlank()) throw new IllegalArgumentException("Source CRS is required");
		if (featureCount < 0 || validBuildings < 0 || skippedInvalidHeight < 0
				|| skippedInvalidGeometry < 0 || validGeometryCount < 0 || repairedGeometryCount < 0) {
			throw new IllegalArgumentException("Dataset counts must not be negative");
		}
		if (schemaVersion == null || schemaVersion.isBlank()) throw new IllegalArgumentException("Schema version is required");
		boundsWgs84 = boundsWgs84 == null ? new Envelope() : new Envelope(boundsWgs84);
		geometryTypes = geometryTypes == null ? Map.of() : Map.copyOf(geometryTypes);
		fields = fields == null ? List.of() : List.copyOf(fields);
		heightCandidates = heightCandidates == null ? List.of() : List.copyOf(heightCandidates);
		heightQuality = heightQuality == null ? HeightQualityStatistics.empty() : heightQuality;
		issues = issues == null ? List.of() : List.copyOf(issues);
	}
}
