package org.osm2world.buildingtiler.tiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Polygon;
import org.osm2world.buildingtiler.domain.BuildingFeature;
import org.osm2world.buildingtiler.support.TestBuildingFactory;

class TileOwnershipPlannerTest {

	private final TileOwnershipPlanner planner = new TileOwnershipPlanner();

	@Test
	void ownershipAndFeatureOrderDoNotDependOnInputOrder() {
		List<BuildingFeature> features = new ArrayList<>();
		for (int i = 0; i < 30; i++) features.add(TestBuildingFactory.rectangle("f" + i,
				116.60 + i * 0.003, 39.9, 0.0002, 0.0002, 10 + i));
		var first = planner.plan(features, 15, 4);
		Collections.shuffle(features, new java.util.Random(99));
		var second = planner.plan(features, 15, 4);
		assertEquals(first.ownershipHash(), second.ownershipHash());
		assertEquals(first.tiles().stream().map(value -> value.tile().toString()).toList(),
				second.tiles().stream().map(value -> value.tile().toString()).toList());
	}

	@Test
	void boundaryAndPolarCoordinatesAreClampedToValidXyzTiles() {
		int last = (1 << 15) - 1;
		assertEquals(last, planner.atWgs84(15, 180, 90).x);
		assertEquals(0, planner.atWgs84(15, -180, 90).x);
		assertEquals(0, planner.atWgs84(15, 0, 90).y);
		assertEquals(last, planner.atWgs84(15, 0, -90).y);
	}

	@Test
	void multipartFeatureHasExactlyOneCentroidOwner() {
		Polygon first = (Polygon)TestBuildingFactory.rectangle("a", 116.6, 39.9,
				0.0002, 0.0002, 10).geometryWgs84();
		Polygon second = (Polygon)first.copy();
		second.apply((org.locationtech.jts.geom.CoordinateFilter)coordinate -> coordinate.x += 0.02);
		second.geometryChanged();
		var multi = TestBuildingFactory.geometryFactory().createMultiPolygon(new Polygon[] { first, second });
		var plan = planner.plan(List.of(new BuildingFeature("multi", multi, 10, Map.of())), 15, 2);
		assertEquals(1, plan.tiles().size());
		assertEquals(1, plan.tiles().get(0).features().size());
		assertTrue(plan.crossTileBuildings() > 0);
		assertEquals(1, plan.largeBuildings());
	}

	@Test
	void crossTileFootprintIsNeverDuplicatedOrClipped() {
		BuildingFeature wide = TestBuildingFactory.rectangle("wide", 116.6, 39.9,
				0.02, 0.002, 18);
		var plan = planner.plan(List.of(wide), 15, 2);
		assertEquals(1, plan.tiles().stream().mapToInt(value -> value.features().size()).sum());
		assertEquals(wide.geometryWgs84(), plan.tiles().get(0).features().get(0).geometryWgs84());
		assertEquals(1, plan.crossTileBuildings());
		assertTrue(plan.warnings().get(0).contains("full footprint"));
	}
}
