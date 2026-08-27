package org.osm2world.buildingtiler.modeling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Locale;

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
		var styled = new BuildingRuleEngine().evaluate(building,
				org.osm2world.buildingtiler.domain.ModelingConfig.defaults());

		var first = mapper.toTags(styled).tags();
		var second = mapper.toTags(styled).tags();

		assertEquals("21.5", first.get("height"));
		assertEquals(first, second);
		assertEquals("yes", first.get("building"));
		assertTrue(first.containsKey("building:levels"));
		assertTrue(first.containsKey("roof:shape"));
		assertTrue(first.containsKey("building:material"));
		assertTrue(first.containsKey("building:colour"));
	}

	@Test
	void numericTagsAreLocaleIndependentAndCarryProvenance() {
		Locale previous = Locale.getDefault();
		try {
			Locale.setDefault(Locale.GERMANY);
			BuildingFeature building = new BuildingFeature("locale", GEOMETRY_FACTORY.createPolygon(new Coordinate[] {
					new Coordinate(116.6, 39.9), new Coordinate(116.601, 39.9),
					new Coordinate(116.601, 39.901), new Coordinate(116.6, 39.901),
					new Coordinate(116.6, 39.9)
			}), 21.125, Map.of("building:material", "wood"));
			var mapping = new OsmTagMapper().toTags(new BuildingRuleEngine().evaluate(building,
					org.osm2world.buildingtiler.domain.ModelingConfig.defaults()));
			assertEquals("21.125", mapping.tags().get("height"));
			assertEquals("wood", mapping.tags().get("building:material"));
			assertEquals("SOURCE", mapping.provenance().get("building:material"));
		} finally {
			Locale.setDefault(previous);
		}
	}

}
