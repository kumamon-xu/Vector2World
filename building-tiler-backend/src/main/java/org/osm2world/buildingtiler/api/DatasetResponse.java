package org.osm2world.buildingtiler.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Envelope;
import org.osm2world.buildingtiler.application.ManagedDataset;
import org.osm2world.buildingtiler.domain.DatasetMetadata;
import org.osm2world.buildingtiler.domain.FieldMetadata;
import org.osm2world.buildingtiler.domain.HeightCandidate;
import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.domain.HeightQualityStatistics;
import org.osm2world.buildingtiler.domain.ImportIssue;
import org.osm2world.buildingtiler.gis.DatasetInspection;
import org.osm2world.buildingtiler.gis.LayerMetadata;

public record DatasetResponse(
		String schemaVersion,
		String datasetId,
		String status,
		Instant createdAt,
		String format,
		String crs,
		String sourceEncoding,
		List<LayerMetadata> layers,
		Map<String, Long> geometryTypes,
		long featureCount,
		long validGeometryCount,
		long skippedInvalidGeometry,
		long repairedGeometryCount,
		List<Double> bboxWgs84,
		List<FieldMetadata> fields,
		List<HeightCandidate> heightCandidates,
		HeightMapping heightMapping,
		HeightQualityStatistics heightQuality,
		List<ImportIssue> issues) {

	public static DatasetResponse from(ManagedDataset dataset, DatasetMetadata materialized) {
		DatasetInspection inspection = dataset.inspection();
		return new DatasetResponse(DatasetMetadata.SCHEMA_VERSION, dataset.id().toString(),
				dataset.status().name(), dataset.createdAt(), inspection.format(), inspection.sourceCrs(),
				inspection.sourceEncoding(), inspection.layers(), inspection.geometryTypes(),
				inspection.featureCount(), inspection.features().size(), inspection.skippedInvalidGeometry(),
				inspection.repairedGeometryCount(), bbox(inspection.boundsWgs84()), inspection.fields(),
				inspection.heightCandidates(), dataset.heightMapping(),
				materialized == null ? null : materialized.heightQuality(), inspection.issues());
	}

	private static List<Double> bbox(Envelope envelope) {
		if (envelope == null || envelope.isNull()) return List.of();
		return List.of(envelope.getMinX(), envelope.getMinY(), envelope.getMaxX(), envelope.getMaxY());
	}
}
