package org.osm2world.buildingtiler.gis;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.locationtech.jts.geom.Envelope;
import org.osm2world.buildingtiler.domain.BuildingFeature;
import org.osm2world.buildingtiler.domain.DatasetMetadata;
import org.osm2world.buildingtiler.domain.FieldMetadata;
import org.osm2world.buildingtiler.domain.HeightCandidate;
import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.domain.HeightQualityStatistics;
import org.osm2world.buildingtiler.domain.ImportIssue;
import org.osm2world.buildingtiler.domain.ImmutableAttributes;
import org.osm2world.buildingtiler.domain.ImportIssue.Severity;
import org.osm2world.buildingtiler.domain.InvalidHeightPolicy;

public record DatasetInspection(
		Path sourcePath,
		String format,
		String sourceCrs,
		String crsSource,
		String sourceEncoding,
		String archiveEntryEncoding,
		boolean archiveEntryEncodingFallback,
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

	private static final Set<String> MODELING_ATTRIBUTE_ALIASES = Set.of(
			"roof:height", "roof_height", "roof:shape", "roof_shape",
			"building:material", "building_material", "roof:material", "roof_material",
			"building:colour", "building:color", "building_colour", "building_color",
			"roof:colour", "roof:color", "roof_colour", "roof_color", "window", "windows");

	public DatasetInspection {
		if (format == null || format.isBlank()) throw new IllegalArgumentException("Format is required");
		if (sourceCrs == null || sourceCrs.isBlank()) throw new IllegalArgumentException("Source CRS is required");
		if (crsSource == null || crsSource.isBlank()) throw new IllegalArgumentException("CRS source is required");
		layers = layers == null ? List.of() : List.copyOf(layers);
		features = features == null ? List.of()
				: NormalizedFeatureStore.isStoreBacked(features) ? features : List.copyOf(features);
		fields = fields == null ? List.of() : List.copyOf(fields);
		heightCandidates = heightCandidates == null ? List.of() : List.copyOf(heightCandidates);
		geometryTypes = geometryTypes == null ? Map.of() : Map.copyOf(geometryTypes);
		boundsWgs84 = boundsWgs84 == null ? new Envelope() : new Envelope(boundsWgs84);
		issues = issues == null ? List.of() : List.copyOf(issues);
	}

	public DatasetReadResult materialize(HeightMapping mapping) throws DatasetImportException {
		return materialize(mapping, ImportDeadline.start(java.time.Duration.ofMinutes(2)));
	}

	public DatasetReadResult materialize(HeightMapping mapping, ImportDeadline deadline)
			throws DatasetImportException {
		deadline.check("height materialization initialization");
		if (mapping == null) throw new IllegalArgumentException("Height mapping is required");
		if (fields.stream().noneMatch(field -> field.name().equals(mapping.fieldName()))) {
			throw new DatasetImportException(DatasetErrorCode.HEIGHT_FIELD_NOT_FOUND,
					"Height field does not exist: " + mapping.fieldName(),
					Map.of("availableFields", fields.stream().map(FieldMetadata::name).toList()));
		}

		List<BuildingFeature> buildings = new ArrayList<>();
		HeightStatisticsAccumulator statistics = new HeightStatisticsAccumulator();
		try {
			for (SourceBuildingFeature feature : features) {
				deadline.check("height parsing and building materialization");
				HeightValueParser.Result parsed = HeightValueParser.parse(feature.properties().get(mapping.fieldName()), mapping);
				statistics.accept(parsed);
				if (!parsed.valid()) {
					if (mapping.invalidPolicy() == InvalidHeightPolicy.FAIL) {
						throw new DatasetImportException(DatasetErrorCode.INVALID_HEIGHT,
								"Feature " + feature.id() + " has invalid height status " + parsed.status());
					}
					continue;
				}
				// Geometry and the immutable source attributes are shared for in-memory inspections.
				// A disk-backed inspection releases each source record after this projection step.
				buildings.add(new BuildingFeature(feature.id(), feature.geometryWgs84(), parsed.meters(),
						modelingAttributes(feature.properties(), mapping.fieldName())));
				ImportMemoryGuard.check(buildings.size(), "materialized building retention");
			}
		} catch (NormalizedFeatureStore.ReadException exception) {
			throw new DatasetImportException(DatasetErrorCode.STORAGE_UNAVAILABLE,
					"Normalized feature store cannot be read: " + exception.getCause().getMessage(), exception);
		}
		deadline.check("height statistics finalization");
		HeightQualityStatistics quality = statistics.result();
		DatasetMetadata metadata = new DatasetMetadata(sourcePath, format, sourceCrs, crsSource, sourceEncoding,
				archiveEntryEncoding, archiveEntryEncodingFallback,
				featureCount, buildings.size(), quality.invalid(), skippedInvalidGeometry,
				boundsWgs84, geometryTypes,
				quality.minimumMeters() == null ? Double.NaN : quality.minimumMeters(),
				quality.maximumMeters() == null ? Double.NaN : quality.maximumMeters(),
				DatasetMetadata.SCHEMA_VERSION, features.size(), repairedGeometryCount,
				fields, heightCandidates, quality, issues);
		return new DatasetReadResult(buildings, metadata);
	}

	public DatasetInspection withArchiveEntryEncoding(String encoding, boolean fallback) {
		List<ImportIssue> updatedIssues = issues;
		if (fallback) {
			updatedIssues = new ArrayList<>(issues);
			updatedIssues.add(new ImportIssue(Severity.WARNING, "ZIP_ENTRY_ENCODING_FALLBACK",
					"ZIP entry names were decoded using " + encoding + " after strict UTF-8 decoding failed", 1));
		}
		return new DatasetInspection(sourcePath, format, sourceCrs, crsSource, sourceEncoding, encoding, fallback,
				layers, featureCount, features, fields, heightCandidates, geometryTypes, boundsWgs84,
				skippedInvalidGeometry, repairedGeometryCount, updatedIssues);
	}

	public DatasetInspection withFeatures(List<SourceBuildingFeature> replacement) {
		return new DatasetInspection(sourcePath, format, sourceCrs, crsSource, sourceEncoding,
				archiveEntryEncoding, archiveEntryEncodingFallback, layers, featureCount, replacement,
				fields, heightCandidates, geometryTypes, boundsWgs84, skippedInvalidGeometry,
				repairedGeometryCount, issues);
	}

	private static Map<String, Object> modelingAttributes(Map<String, Object> source, String heightField) {
		Map<String, Object> projected = new LinkedHashMap<>();
		for (var entry : source.entrySet()) {
			String lower = entry.getKey().toLowerCase(Locale.ROOT);
			if (entry.getKey().equals(heightField) || MODELING_ATTRIBUTE_ALIASES.contains(lower)) {
				projected.put(entry.getKey(), entry.getValue());
			}
		}
		return ImmutableAttributes.copyOf(projected);
	}
}
