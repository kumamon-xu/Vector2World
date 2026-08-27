package org.osm2world.buildingtiler.gis;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Envelope;
import org.osm2world.buildingtiler.domain.BuildingFeature;
import org.osm2world.buildingtiler.domain.DatasetMetadata;
import org.osm2world.buildingtiler.domain.FieldMetadata;
import org.osm2world.buildingtiler.domain.HeightCandidate;
import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.domain.HeightQualityStatistics;
import org.osm2world.buildingtiler.domain.ImportIssue;
import org.osm2world.buildingtiler.domain.InvalidHeightPolicy;

public record DatasetInspection(
		Path sourcePath,
		String format,
		String sourceCrs,
		String sourceEncoding,
		List<LayerMetadata> layers,
		long featureCount,
		List<SourceBuildingFeature> features,
		List<FieldMetadata> fields,
		List<HeightCandidate> heightCandidates,
		Map<String, Long> geometryTypes,
		Envelope boundsWgs84,
		long skippedInvalidGeometry,
		long repairedGeometryCount,
		List<ImportIssue> issues) {

	public DatasetInspection {
		if (format == null || format.isBlank()) throw new IllegalArgumentException("Format is required");
		if (sourceCrs == null || sourceCrs.isBlank()) throw new IllegalArgumentException("Source CRS is required");
		layers = layers == null ? List.of() : List.copyOf(layers);
		features = features == null ? List.of() : List.copyOf(features);
		fields = fields == null ? List.of() : List.copyOf(fields);
		heightCandidates = heightCandidates == null ? List.of() : List.copyOf(heightCandidates);
		geometryTypes = geometryTypes == null ? Map.of() : Map.copyOf(geometryTypes);
		boundsWgs84 = boundsWgs84 == null ? new Envelope() : new Envelope(boundsWgs84);
		issues = issues == null ? List.of() : List.copyOf(issues);
	}

	public DatasetReadResult materialize(HeightMapping mapping) throws DatasetImportException {
		if (mapping == null) throw new IllegalArgumentException("Height mapping is required");
		if (fields.stream().noneMatch(field -> field.name().equals(mapping.fieldName()))) {
			throw new DatasetImportException(DatasetErrorCode.HEIGHT_FIELD_NOT_FOUND,
					"Height field does not exist: " + mapping.fieldName(),
					Map.of("availableFields", fields.stream().map(FieldMetadata::name).toList()));
		}

		List<BuildingFeature> buildings = new ArrayList<>();
		HeightStatisticsAccumulator statistics = new HeightStatisticsAccumulator();
		for (SourceBuildingFeature feature : features) {
			HeightValueParser.Result parsed = HeightValueParser.parse(feature.properties().get(mapping.fieldName()), mapping);
			statistics.accept(parsed);
			if (!parsed.valid()) {
				if (mapping.invalidPolicy() == InvalidHeightPolicy.FAIL) {
					throw new DatasetImportException(DatasetErrorCode.INVALID_HEIGHT,
							"Feature " + feature.id() + " has invalid height status " + parsed.status());
				}
				continue;
			}
			buildings.add(new BuildingFeature(feature.id(), feature.geometryWgs84(), parsed.meters(),
					Map.of("heightField", mapping.fieldName(), "partIds",
							feature.parts().stream().map(Object::toString).toList())));
		}
		HeightQualityStatistics quality = statistics.result();
		DatasetMetadata metadata = new DatasetMetadata(sourcePath, format, sourceCrs, sourceEncoding,
				featureCount, buildings.size(), quality.invalid(), skippedInvalidGeometry,
				boundsWgs84, geometryTypes,
				quality.minimumMeters() == null ? Double.NaN : quality.minimumMeters(),
				quality.maximumMeters() == null ? Double.NaN : quality.maximumMeters(),
				DatasetMetadata.SCHEMA_VERSION, features.size(), repairedGeometryCount,
				fields, heightCandidates, quality, issues);
		return new DatasetReadResult(buildings, metadata);
	}
}
