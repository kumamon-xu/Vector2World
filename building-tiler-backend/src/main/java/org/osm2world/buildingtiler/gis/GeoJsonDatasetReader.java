package org.osm2world.buildingtiler.gis;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.osm2world.buildingtiler.domain.BuildingPartId;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

public final class GeoJsonDatasetReader implements InspectingDatasetReader {

	@Override
	public DatasetInspection inspect(Path input, ImportOptions options) throws IOException {
		if (!Files.isRegularFile(input)) throw new DatasetImportException(DatasetErrorCode.INVALID_GEOJSON,
				"GeoJSON file does not exist");
		Instant deadline = Instant.now().plus(options.timeout());
		checkDeadline(deadline);
		Charset encoding = CharsetProbe.utf8OrGb18030(input);
		List<RawFeature> rawFeatures = new ArrayList<>();
		FieldProfiler fields = new FieldProfiler();
		ImportIssueCollector issues = new ImportIssueCollector();
		Map<String, Long> geometryTypes = new LinkedHashMap<>();
		String declaredCrs = "OGC:CRS84";
		String rootType = null;
		long featureCount = 0;

		try (var fileReader = new InputStreamReader(Files.newInputStream(input), encoding);
			 JsonReader reader = new JsonReader(fileReader)) {
			reader.beginObject();
			while (reader.hasNext()) {
				String name = reader.nextName();
				switch (name) {
					case "type" -> rootType = reader.nextString();
					case "crs" -> declaredCrs = readCrs(JsonParser.parseReader(reader));
					case "features" -> {
						reader.beginArray();
						while (reader.hasNext()) {
							checkDeadline(deadline);
							featureCount++;
							JsonElement element = JsonParser.parseReader(reader);
							if (!element.isJsonObject()) {
								issues.error("INVALID_FEATURE", "Feature is not a JSON object");
								continue;
							}
							JsonObject feature = element.getAsJsonObject();
							Map<String, Object> properties = properties(feature.get("properties"));
							fields.accept(properties);
							JsonObject geometryObject = object(feature.get("geometry"));
							String geometryType = geometryObject == null ? "null" : string(geometryObject.get("type"));
							geometryTypes.merge(geometryType == null ? "null" : geometryType, 1L, Long::sum);
							try {
								Geometry geometry = geometryObject == null ? null : GeoJsonGeometryParser.parse(geometryObject);
								String sourceId = primitiveString(feature.get("id"));
								rawFeatures.add(new RawFeature(sourceId, geometry, properties,
										geometryType == null ? "null" : geometryType));
							} catch (RuntimeException badGeometry) {
								issues.error("INVALID_GEOMETRY", "GeoJSON geometry cannot be parsed");
								rawFeatures.add(new RawFeature(null, null, properties,
										geometryType == null ? "null" : geometryType));
							}
						}
						reader.endArray();
					}
					default -> reader.skipValue();
				}
			}
			reader.endObject();
		} catch (DatasetImportException exception) {
			throw exception;
		} catch (IOException | IllegalStateException | JsonParseException exception) {
			throw new DatasetImportException(DatasetErrorCode.INVALID_GEOJSON,
					"Invalid GeoJSON FeatureCollection: " + exception.getMessage(), exception);
		}
		if (!"FeatureCollection".equals(rootType)) {
			throw new DatasetImportException(DatasetErrorCode.INVALID_GEOJSON,
					"GeoJSON root type must be FeatureCollection");
		}

		CrsSupport.ResolvedCrs crs = CrsSupport.resolve(declaredCrs, options.explicitCrs());
		List<SourceBuildingFeature> accepted = new ArrayList<>();
		Envelope bounds = new Envelope();
		Map<String, Integer> ids = new HashMap<>();
		long skipped = 0;
		long repaired = 0;
		for (RawFeature raw : rawFeatures) {
			if (raw.geometry == null) {
				skipped++;
				issues.error("UNSUPPORTED_OR_MISSING_GEOMETRY", "Feature has no polygonal geometry");
				continue;
			}
			Geometry wgs84 = CrsSupport.toWgs84(raw.geometry, crs);
			GeometryNormalizer.Result normalized = GeometryNormalizer.normalize(wgs84, options);
			if (!normalized.accepted()) {
				skipped++;
				issues.error(normalized.rejectionCode(), normalized.rejectionMessage());
				continue;
			}
			if (normalized.repaired()) repaired++;
			if (normalized.warning() != null) issues.warning("REPAIR_AREA_CHANGED", normalized.warning());
			String baseId = StableIdGenerator.baseId(raw.sourceId, normalized.geometry(), raw.properties);
			int occurrence = ids.merge(baseId, 1, Integer::sum);
			String id = occurrence == 1 ? baseId
					: baseId + "~" + StableIdGenerator.collisionSuffix(baseId, normalized.geometry(), raw.properties)
							+ "-" + occurrence;
			if (occurrence > 1) issues.warning("DUPLICATE_FEATURE_ID", "Duplicate source feature ID was disambiguated");
			int parts = normalized.geometry() instanceof MultiPolygon multi ? multi.getNumGeometries() : 1;
			List<BuildingPartId> partIds = new ArrayList<>(parts);
			for (int part = 0; part < parts; part++) partIds.add(new BuildingPartId(id, part));
			accepted.add(new SourceBuildingFeature(id, normalized.geometry(), raw.properties, partIds,
					raw.geometryType, normalized.repaired()));
			bounds.expandToInclude(normalized.geometry().getEnvelopeInternal());
		}

		return new DatasetInspection(input.toAbsolutePath(), "GEOJSON", crs.name(), encoding.name(),
				List.of(new LayerMetadata(input.getFileName().toString(), dominantType(geometryTypes), true)),
				featureCount, accepted, fields.metadata(), fields.heightCandidates(), geometryTypes,
				bounds, skipped, repaired, issues.result());
	}

	private static void checkDeadline(Instant deadline) throws DatasetImportException {
		if (Thread.currentThread().isInterrupted() || Instant.now().isAfter(deadline)) {
			throw new DatasetImportException(DatasetErrorCode.IMPORT_TIMEOUT, "Dataset import timed out or was cancelled");
		}
	}

	private static Map<String, Object> properties(JsonElement value) {
		JsonObject object = object(value);
		if (object == null) return Map.of();
		Map<String, Object> result = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			JsonElement item = entry.getValue();
			if (item == null || item.isJsonNull()) result.put(entry.getKey(), null);
			else if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isBoolean()) result.put(entry.getKey(), item.getAsBoolean());
			else if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isNumber()) result.put(entry.getKey(), item.getAsDouble());
			else if (item.isJsonPrimitive()) result.put(entry.getKey(), item.getAsString());
			else result.put(entry.getKey(), item.toString());
		}
		return result;
	}

	private static String readCrs(JsonElement value) {
		JsonObject crs = object(value);
		if (crs == null) return "OGC:CRS84";
		JsonObject properties = object(crs.get("properties"));
		String name = properties == null ? null : string(properties.get("name"));
		return name == null ? "OGC:CRS84" : name;
	}

	private static String dominantType(Map<String, Long> types) {
		return types.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("Unknown");
	}

	private static JsonObject object(JsonElement value) {
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
	}

	private static String string(JsonElement value) {
		return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
	}

	private static String primitiveString(JsonElement value) {
		return value != null && !value.isJsonNull() && value.isJsonPrimitive() ? value.getAsString() : null;
	}

	private record RawFeature(String sourceId, Geometry geometry, Map<String, Object> properties,
			String geometryType) {}
}
