package org.osm2world.buildingtiler.gis;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

final class GeoJsonGeometryParser {

	private static final GeometryFactory FACTORY = new GeometryFactory();

	private GeoJsonGeometryParser() {}

	static Geometry parse(JsonObject geometry) {
		String type = string(geometry.get("type"));
		if (type == null) return null;
		return switch (type) {
			case "Polygon" -> parsePolygon(array(geometry.get("coordinates")));
			case "MultiPolygon" -> parseMultiPolygon(array(geometry.get("coordinates")));
			case "GeometryCollection" -> parseCollection(array(geometry.get("geometries")));
			default -> null;
		};
	}

	private static Geometry parseMultiPolygon(JsonArray polygons) {
		if (polygons == null) return null;
		Polygon[] result = new Polygon[polygons.size()];
		for (int i = 0; i < polygons.size(); i++) {
			result[i] = parsePolygon(array(polygons.get(i)));
			if (result[i] == null) return null;
		}
		return FACTORY.createMultiPolygon(result);
	}

	private static Geometry parseCollection(JsonArray geometries) {
		if (geometries == null) return null;
		List<Geometry> result = new ArrayList<>();
		for (JsonElement element : geometries) {
			if (!element.isJsonObject()) continue;
			Geometry parsed = parse(element.getAsJsonObject());
			if (parsed != null) result.add(parsed);
		}
		return FACTORY.createGeometryCollection(result.toArray(Geometry[]::new));
	}

	private static Polygon parsePolygon(JsonArray rings) {
		if (rings == null || rings.isEmpty()) return null;
		LinearRing shell = parseRing(array(rings.get(0)));
		if (shell == null) return null;
		LinearRing[] holes = new LinearRing[Math.max(0, rings.size() - 1)];
		for (int i = 1; i < rings.size(); i++) {
			holes[i - 1] = parseRing(array(rings.get(i)));
			if (holes[i - 1] == null) return null;
		}
		return FACTORY.createPolygon(shell, holes);
	}

	private static LinearRing parseRing(JsonArray positions) {
		if (positions == null || positions.size() < 3) return null;
		List<Coordinate> coordinates = new ArrayList<>(positions.size() + 1);
		for (JsonElement element : positions) {
			JsonArray position = array(element);
			if (position == null || position.size() < 2) return null;
			double x = position.get(0).getAsDouble();
			double y = position.get(1).getAsDouble();
			if (!Double.isFinite(x) || !Double.isFinite(y)) return null;
			coordinates.add(new Coordinate(x, y));
		}
		if (!coordinates.get(0).equals2D(coordinates.get(coordinates.size() - 1))) {
			coordinates.add(coordinates.get(0).copy());
		}
		if (coordinates.size() < 4) return null;
		return FACTORY.createLinearRing(coordinates.toArray(Coordinate[]::new));
	}

	private static JsonArray array(JsonElement value) {
		return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
	}

	private static String string(JsonElement value) {
		return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
	}
}
