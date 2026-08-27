package org.osm2world.buildingtiler.application;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import org.osm2world.buildingtiler.gis.DatasetInspection;
import org.osm2world.buildingtiler.gis.SourceBuildingFeature;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class PreviewGeoJsonService {

	public static final int DEFAULT_MAX_FEATURES = 500;
	public static final int DEFAULT_MAX_VERTICES = 50_000;
	public static final int DEFAULT_MAX_RESPONSE_BYTES = 5 * 1024 * 1024;

	private final ObjectMapper mapper;
	private final int maxFeatures;
	private final int maxVertices;
	private final int maxResponseBytes;

	public PreviewGeoJsonService(ObjectMapper mapper) {
		this(mapper, DEFAULT_MAX_FEATURES, DEFAULT_MAX_VERTICES, DEFAULT_MAX_RESPONSE_BYTES);
	}

	public PreviewGeoJsonService(ObjectMapper mapper, int maxFeatures, int maxVertices, int maxResponseBytes) {
		this.mapper = mapper;
		this.maxFeatures = maxFeatures;
		this.maxVertices = maxVertices;
		this.maxResponseBytes = maxResponseBytes;
	}

	public String render(DatasetInspection inspection) throws JsonProcessingException {
		List<SourceBuildingFeature> ordered = inspection.features().stream()
				.sorted(Comparator.comparing(SourceBuildingFeature::id)).toList();
		int limit = Math.min(maxFeatures, ordered.size());
		while (true) {
			List<SourceBuildingFeature> sampled = stableSample(ordered, limit);
			ObjectNode root = featureCollection(inspection.boundsWgs84(), sampled);
			String json = mapper.writeValueAsString(root);
			if (json.getBytes(UTF_8).length <= maxResponseBytes || limit == 0) return json;
			limit /= 2;
		}
	}

	private ObjectNode featureCollection(Envelope datasetBounds, List<SourceBuildingFeature> sampled) {
		ObjectNode root = mapper.createObjectNode();
		root.put("type", "FeatureCollection");
		if (datasetBounds != null && !datasetBounds.isNull()) root.set("bbox", bbox(datasetBounds));
		ArrayNode features = root.putArray("features");
		int remainingVertices = maxVertices;
		for (int i = 0; i < sampled.size() && remainingVertices >= 4; i++) {
			SourceBuildingFeature source = sampled.get(i);
			int remainingFeatures = sampled.size() - i;
			int allowance = Math.max(4, remainingVertices / remainingFeatures);
			Geometry geometry = simplifyToBudget(source.geometryWgs84(), allowance, datasetBounds);
			int vertices = geometry.getNumPoints();
			if (vertices > remainingVertices) continue;
			remainingVertices -= vertices;
			ObjectNode feature = features.addObject();
			feature.put("type", "Feature");
			feature.put("id", source.id());
			ObjectNode properties = feature.putObject("properties");
			properties.put("partCount", source.parts().size());
			feature.set("geometry", geometry(geometry));
		}
		root.putObject("previewLimits")
				.put("maxFeatures", maxFeatures)
				.put("maxVertices", maxVertices)
				.put("maxResponseBytes", maxResponseBytes);
		return root;
	}

	private Geometry simplifyToBudget(Geometry source, int allowance, Envelope bounds) {
		if (source.getNumPoints() <= allowance) return source;
		double scale = bounds == null || bounds.isNull() ? 1e-6
				: Math.max(bounds.getWidth(), bounds.getHeight());
		double tolerance = Math.max(1e-12, scale / 1_000_000.0);
		Geometry candidate = source;
		for (int attempt = 0; attempt < 12 && candidate.getNumPoints() > allowance; attempt++) {
			Geometry simplified = TopologyPreservingSimplifier.simplify(source, tolerance);
			if (!simplified.isEmpty() && simplified.isValid()
					&& (simplified instanceof Polygon || simplified instanceof MultiPolygon)) candidate = simplified;
			tolerance *= 2;
		}
		return candidate;
	}

	private ObjectNode geometry(Geometry geometry) {
		ObjectNode json = mapper.createObjectNode();
		if (geometry instanceof Polygon polygon) {
			json.put("type", "Polygon");
			json.set("coordinates", polygonCoordinates(polygon));
		} else if (geometry instanceof MultiPolygon multiPolygon) {
			json.put("type", "MultiPolygon");
			ArrayNode polygons = mapper.createArrayNode();
			for (int i = 0; i < multiPolygon.getNumGeometries(); i++) {
				polygons.add(polygonCoordinates((Polygon) multiPolygon.getGeometryN(i)));
			}
			json.set("coordinates", polygons);
		} else {
			throw new IllegalArgumentException("Preview accepts polygonal geometry only");
		}
		return json;
	}

	private ArrayNode polygonCoordinates(Polygon polygon) {
		ArrayNode rings = mapper.createArrayNode();
		rings.add(ring((LinearRing) polygon.getExteriorRing()));
		for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
			rings.add(ring((LinearRing) polygon.getInteriorRingN(i)));
		}
		return rings;
	}

	private ArrayNode ring(LinearRing ring) {
		ArrayNode positions = mapper.createArrayNode();
		for (Coordinate coordinate : ring.getCoordinates()) {
			positions.add(mapper.createArrayNode().add(coordinate.x).add(coordinate.y));
		}
		return positions;
	}

	private ArrayNode bbox(Envelope envelope) {
		return mapper.createArrayNode().add(envelope.getMinX()).add(envelope.getMinY())
				.add(envelope.getMaxX()).add(envelope.getMaxY());
	}

	private static List<SourceBuildingFeature> stableSample(List<SourceBuildingFeature> ordered, int limit) {
		if (limit >= ordered.size()) return ordered;
		if (limit <= 0) return List.of();
		List<SourceBuildingFeature> result = new ArrayList<>(limit);
		for (int i = 0; i < limit; i++) {
			int index = (int) (((long) i * ordered.size()) / limit);
			result.add(ordered.get(index));
		}
		return result;
	}
}
