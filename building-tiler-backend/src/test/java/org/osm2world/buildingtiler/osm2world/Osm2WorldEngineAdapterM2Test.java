package org.osm2world.buildingtiler.osm2world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.osm2world.buildingtiler.domain.BuildingFeature;
import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.domain.RoofMode;
import org.osm2world.buildingtiler.modeling.BuildingRuleEngine;
import org.osm2world.buildingtiler.modeling.OsmTagMapper;
import org.osm2world.buildingtiler.support.TestBuildingFactory;
import org.osm2world.O2WConverter;
import org.osm2world.map_data.data.MapData;
import org.osm2world.math.geo.MapProjection;
import org.osm2world.output.Output;
import org.osm2world.scene.Scene;
import org.osm2world.math.geo.LatLon;
import org.osm2world.math.geo.TileNumber;

class Osm2WorldEngineAdapterM2Test {

	private final Osm2WorldEngineAdapter engine = new Osm2WorldEngineAdapter(
			new OsmTagMapper(), new BuildingRuleEngine());
	private final TileNumber tile = TileNumber.atLatLon(15, new LatLon(39.9, 116.6));

	@Test
	void mappedTagsReachLockedOsm2WorldAndExactHeightControlsMesh() {
		BuildingFeature feature = TestBuildingFactory.rectangle("exact", 116.6, 39.9,
				0.0002, 0.00015, 21.125, Map.of("building:material", "brick"));
		var result = engine.generate(tile, List.of(feature),
				ModelingConfig.defaults().withRoofMode(RoofMode.CONSERVATIVE), false);
		assertFalse(result.empty());
		assertEquals(1, result.modeledFeatures());
		assertEquals(21.125, result.metrics().maximumY(), 1e-6);
		var tags = result.scene().getMapData().getMapAreas().iterator().next().getTags();
		assertEquals("21.125", tags.getValue("height"));
		assertEquals("flat", tags.getValue("roof:shape"));
		assertEquals("brick", tags.getValue("building:material"));
		assertTrue(result.meshCount() > 0);
	}

	@Test
	void supportsHolesAndMultipartWhileIsolatingOneBadFeature() {
		var factory = TestBuildingFactory.geometryFactory();
		LinearRing shell = factory.createLinearRing(new Coordinate[] {
				new Coordinate(116.6, 39.9), new Coordinate(116.601, 39.9),
				new Coordinate(116.601, 39.901), new Coordinate(116.6, 39.901),
				new Coordinate(116.6, 39.9) });
		LinearRing hole = factory.createLinearRing(new Coordinate[] {
				new Coordinate(116.6003, 39.9003), new Coordinate(116.6007, 39.9003),
				new Coordinate(116.6007, 39.9007), new Coordinate(116.6003, 39.9007),
				new Coordinate(116.6003, 39.9003) });
		Polygon withHole = factory.createPolygon(shell, new LinearRing[] { hole });
		Polygon second = (Polygon)withHole.copy();
		second.apply((org.locationtech.jts.geom.CoordinateFilter)value -> value.x += 0.002);
		second.geometryChanged();
		var multi = factory.createMultiPolygon(new Polygon[] { withHole, second });
		BuildingFeature good = new BuildingFeature("multi", multi, 18, Map.of());
		Polygon invalid = TestBuildingFactory.polygon(new Coordinate[] {
				new Coordinate(Double.POSITIVE_INFINITY, 39.9), new Coordinate(116.7, 39.9),
				new Coordinate(116.7, 39.91), new Coordinate(Double.POSITIVE_INFINITY, 39.9) });
		BuildingFeature bad = new BuildingFeature("bad", invalid, 10, Map.of());
		var result = engine.generate(tile, List.of(good, bad), ModelingConfig.defaults(), false);
		assertEquals(1, result.modeledFeatures());
		assertEquals(1, result.failures().size());
		assertEquals("bad", result.failures().get(0).featureId());
		assertTrue(result.scene().getMapData().getMapAreas().stream().anyMatch(area -> !area.getHoles().isEmpty()));
	}

	@Test
	void emptyTileReturnsExplicitEmptyResult() {
		assertTrue(engine.generate(tile, List.of(), ModelingConfig.defaults(), false).empty());
	}

	@Test
	void adapterHasNoCrossRequestMutableState() throws Exception {
		var feature = TestBuildingFactory.rectangle("concurrent", 116.6, 39.9, .0002, .0002, 18);
		var executor = Executors.newFixedThreadPool(4);
		try {
			List<Callable<String>> calls = java.util.stream.IntStream.range(0, 8)
					.<Callable<String>>mapToObj(index -> () -> engine.generate(tile, List.of(feature),
							ModelingConfig.defaults(), false).styles().get(0).style().outputHash()).toList();
			var hashes = executor.invokeAll(calls).stream().map(future -> {
				try { return future.get(); }
				catch (Exception exception) { throw new IllegalStateException(exception); }
			}).distinct().toList();
			assertEquals(1, hashes.size());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void isolatesAFeatureThatFailsInsideOsm2WorldConversion() {
		AtomicInteger conversions = new AtomicInteger();
		var isolatingEngine = new Osm2WorldEngineAdapter(new OsmTagMapper(), new BuildingRuleEngine(),
				new Osm2WorldConfigFactory(), () -> new O2WConverter() {
					@Override public Scene convert(MapData mapData, MapProjection projection, Output... outputs) {
						int invocation = conversions.incrementAndGet();
						if (invocation == 1 || invocation == 3) {
							throw new IllegalStateException("injected conversion failure");
						}
						return super.convert(mapData, projection, outputs);
					}
				});
		var first = TestBuildingFactory.rectangle("first", 116.6000, 39.9000, .0002, .0002, 18);
		var poisoned = TestBuildingFactory.rectangle("poisoned", 116.6004, 39.9000, .0002, .0002, 18);
		var third = TestBuildingFactory.rectangle("third", 116.6008, 39.9000, .0002, .0002, 18);

		var result = isolatingEngine.generate(tile, List.of(first, poisoned, third),
				ModelingConfig.defaults(), false);

		assertFalse(result.empty());
		assertEquals(2, result.modeledFeatures());
		assertTrue(result.failures().stream().anyMatch(failure -> failure.featureId().equals("poisoned")
				&& failure.category() == ModelFailureCategory.OSM2WORLD_CONVERSION));
		assertTrue(result.ledger().stream().anyMatch(entry -> entry.sourceFeatureId().equals("poisoned")
				&& entry.status() == ModelingLedgerEntry.Status.FAILED_O2W_CONVERSION));
		assertTrue(result.ledger().stream().filter(entry -> entry.status() == ModelingLedgerEntry.Status.PENDING)
				.map(ModelingLedgerEntry::sourceFeatureId).distinct().count() == 2);
	}
}
