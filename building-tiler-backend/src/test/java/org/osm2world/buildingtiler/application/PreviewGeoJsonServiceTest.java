package org.osm2world.buildingtiler.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.osm2world.buildingtiler.domain.BuildingPartId;
import org.osm2world.buildingtiler.gis.DatasetInspection;
import org.osm2world.buildingtiler.gis.LayerMetadata;
import org.osm2world.buildingtiler.gis.SourceBuildingFeature;

import com.fasterxml.jackson.databind.ObjectMapper;

class PreviewGeoJsonServiceTest {

	@Test
	void previewUsesDeterministicSamplingBudgetsAndNeverLeaksSourceAttributes() throws Exception {
		GeometryFactory factory = new GeometryFactory();
		List<SourceBuildingFeature> features = new ArrayList<>();
		Envelope bounds = new Envelope();
		for (int i = 0; i < 100; i++) {
			double x = 100 + i * 0.001;
			var polygon = factory.createPolygon(new Coordinate[] {
					new Coordinate(x, 30), new Coordinate(x + 0.0005, 30),
					new Coordinate(x + 0.0005, 30.0005), new Coordinate(x, 30.0005),
					new Coordinate(x, 30) });
			String id = "feature-" + i;
			features.add(new SourceBuildingFeature(id, polygon, Map.of("secret", "must-not-leak"),
					List.of(new BuildingPartId(id, 0)), "Polygon", false));
			bounds.expandToInclude(polygon.getEnvelopeInternal());
		}
		DatasetInspection inspection = new DatasetInspection(null, "GEOJSON", "OGC:CRS84", "DECLARED_VALID", "UTF-8", null, false,
				List.of(new LayerMetadata("test", "Polygon", true)), 100, features, List.of(), List.of(),
				Map.of("Polygon", 100L), bounds, 0, 0, List.of());
		ObjectMapper mapper = new ObjectMapper();
		PreviewGeoJsonService service = new PreviewGeoJsonService(mapper, 10, 50, 20_000);

		String first = service.render(inspection);
		String second = service.render(inspection);
		assertEquals(first, second);
		var json = mapper.readTree(first);
		assertTrue(json.get("features").size() <= 10);
		assertEquals(4, json.get("bbox").size());
		assertFalse(first.contains("must-not-leak"));
		assertFalse(first.contains("secret"));
		assertTrue(first.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 20_000);
	}
}
