package org.osm2world.buildingtiler.modeling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.osm2world.buildingtiler.domain.BuildingFeature;

class OsmTagMapperTest {

	private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

	@Test
	void mapsElevationToExactHeightAndProducesStableProceduralTags() {
		BuildingFeature building = new BuildingFeature("stable-id", GEOMETRY_FACTORY.createPolygon(new Coordinate[] {
				new Coordinate(116.6, 39.9), new Coordinate(116.601, 39.9),
				new Coordinate(116.601, 39.901), new Coordinate(116.6, 39.901),
				new Coordinate(116.6, 39.9)
		}), 21.5, Map.of());
		OsmTagMapper mapper = new OsmTagMapper();

		var first = mapper.toTags(building);
		var second = mapper.toTags(building);

		assertEquals("21.5", first.getValue("height"));
		assertEquals(first, second);
		assertEquals("yes", first.getValue("building"));
		assertTrue(first.containsKey("building:levels"));
		assertTrue(first.containsKey("roof:shape"));
		assertTrue(first.containsKey("building:material"));
		assertTrue(first.containsKey("building:colour"));
	}

}
