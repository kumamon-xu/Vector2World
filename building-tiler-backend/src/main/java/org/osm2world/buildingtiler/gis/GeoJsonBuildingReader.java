package org.osm2world.buildingtiler.gis;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.osm2world.buildingtiler.domain.BuildingFeature;
import org.osm2world.buildingtiler.domain.DatasetMetadata;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

public final class GeoJsonBuildingReader implements DatasetReader {

	private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

	@Override
	public DatasetReadResult read(Path input, String heightField) throws IOException {

		List<BuildingFeature> buildings = new ArrayList<>();
		Map<String, Long> geometryTypes = new LinkedHashMap<>();
		Envelope bounds = new Envelope();
		long featureCount = 0;
		long skippedHeight = 0;
		long skippedGeometry = 0;
		String sourceCrs = "OGC:CRS84";
		Charset sourceEncoding = detectEncoding(input);

		try (Reader fileReader = new InputStreamReader(Files.newInputStream(input), sourceEncoding);
			 JsonReader reader = new JsonReader(fileReader)) {

			reader.beginObject();
			while (reader.hasNext()) {
				String name = reader.nextName();
				switch (name) {
					case "crs" -> sourceCrs = readCrs(JsonParser.parseReader(reader));
					case "features" -> {
						reader.beginArray();
						while (reader.hasNext()) {
							JsonObject feature = JsonParser.parseReader(reader).getAsJsonObject();
							featureCount++;
							JsonObject properties = objectOrEmpty(feature.get("properties"));
							Double height = parseJsonHeight(properties.get(heightField));
							if (height == null) {
								skippedHeight++;
								continue;
							}

							JsonObject geometryObject = objectOrEmpty(feature.get("geometry"));
							String geometryType = stringOrNull(geometryObject.get("type"));
							geometryTypes.merge(geometryType == null ? "null" : geometryType, 1L, Long::sum);
							Geometry geometry = GeometrySupport.normalizePolygonal(parseGeometry(geometryObject));
							if (geometry == null) {
								skippedGeometry++;
								continue;
							}

							String id = feature.has("id")
									? feature.get("id").getAsString()
									: String.format("geojson-%06d", featureCount);
							buildings.add(new BuildingFeature(id, geometry, height,
									Map.of("sourceIndex", featureCount, "heightField", heightField)));
							bounds.expandToInclude(geometry.getEnvelopeInternal());
						}
						reader.endArray();
					}
					default -> reader.skipValue();
				}
			}
			reader.endObject();
		}

		if (!isWgs84(sourceCrs)) {
			throw new IOException("M0 GeoJSON reader only accepts CRS84/EPSG:4326, found: " + sourceCrs);
		}
		if (buildings.isEmpty()) {
			throw new IOException("No valid buildings found in " + input);
		}

		double minHeight = buildings.stream().mapToDouble(BuildingFeature::heightMeters).min().orElseThrow();
		double maxHeight = buildings.stream().mapToDouble(BuildingFeature::heightMeters).max().orElseThrow();
		DatasetMetadata metadata = new DatasetMetadata(input.toAbsolutePath(), "GEOJSON", sourceCrs,
				sourceEncoding.name(),
				featureCount, buildings.size(), skippedHeight, skippedGeometry, bounds,
				Map.copyOf(geometryTypes), minHeight, maxHeight);
		return new DatasetReadResult(buildings, metadata);
	}

	private static Charset detectEncoding(Path input) throws IOException {
		byte[] bytes = Files.readAllBytes(input);
		try {
			StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes));
			return StandardCharsets.UTF_8;
		} catch (CharacterCodingException invalidUtf8) {
			Charset gb18030 = Charset.forName("GB18030");
			try {
				gb18030.newDecoder()
						.onMalformedInput(CodingErrorAction.REPORT)
						.onUnmappableCharacter(CodingErrorAction.REPORT)
						.decode(ByteBuffer.wrap(bytes));
				return gb18030;
			} catch (CharacterCodingException invalidGb18030) {
				throw new IOException("GeoJSON is neither valid UTF-8 nor GB18030: " + input, invalidUtf8);
			}
		}
	}

	private static Geometry parseGeometry(JsonObject geometry) {
		String type = stringOrNull(geometry.get("type"));
		JsonElement coordinates = geometry.get("coordinates");
		if (type == null || coordinates == null || !coordinates.isJsonArray()) return null;

		return switch (type) {
			case "Polygon" -> parsePolygon(coordinates.getAsJsonArray());
			case "MultiPolygon" -> {
				JsonArray polygons = coordinates.getAsJsonArray();
				Polygon[] result = new Polygon[polygons.size()];
				for (int i = 0; i < polygons.size(); i++) {
					result[i] = parsePolygon(polygons.get(i).getAsJsonArray());
					if (result[i] == null) yield null;
				}
				yield GEOMETRY_FACTORY.createMultiPolygon(result);
			}
			default -> null;
		};
	}

	private static Polygon parsePolygon(JsonArray rings) {
		if (rings.isEmpty()) return null;
		LinearRing shell = parseRing(rings.get(0).getAsJsonArray());
		if (shell == null) return null;
		LinearRing[] holes = new LinearRing[Math.max(0, rings.size() - 1)];
		for (int i = 1; i < rings.size(); i++) {
			holes[i - 1] = parseRing(rings.get(i).getAsJsonArray());
			if (holes[i - 1] == null) return null;
		}
		return GEOMETRY_FACTORY.createPolygon(shell, holes);
	}

	private static LinearRing parseRing(JsonArray positions) {
		if (positions.size() < 3) return null;
		List<Coordinate> coordinates = new ArrayList<>(positions.size() + 1);
		for (JsonElement positionElement : positions) {
			JsonArray position = positionElement.getAsJsonArray();
			if (position.size() < 2) return null;
			coordinates.add(new Coordinate(position.get(0).getAsDouble(), position.get(1).getAsDouble()));
		}
		if (!coordinates.get(0).equals2D(coordinates.get(coordinates.size() - 1))) {
			coordinates.add(coordinates.get(0).copy());
		}
		if (coordinates.size() < 4) return null;
		return GEOMETRY_FACTORY.createLinearRing(coordinates.toArray(Coordinate[]::new));
	}

	private static String readCrs(JsonElement crs) {
		if (crs == null || !crs.isJsonObject()) return "OGC:CRS84";
		JsonObject properties = objectOrEmpty(crs.getAsJsonObject().get("properties"));
		String name = stringOrNull(properties.get("name"));
		return name == null ? "OGC:CRS84" : name;
	}

	private static boolean isWgs84(String crs) {
		String normalized = crs.toUpperCase();
		return normalized.contains("CRS84") || normalized.contains("4326") || normalized.contains("WGS_1984");
	}

	private static Double parseJsonHeight(JsonElement value) {
		if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return null;
		return GeometrySupport.parseHeight(value.getAsString());
	}

	private static JsonObject objectOrEmpty(JsonElement value) {
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
	}

	private static String stringOrNull(JsonElement value) {
		return value != null && !value.isJsonNull() && value.isJsonPrimitive() ? value.getAsString() : null;
	}

}
