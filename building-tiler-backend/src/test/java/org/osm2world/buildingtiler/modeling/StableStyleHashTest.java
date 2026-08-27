package org.osm2world.buildingtiler.modeling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.osm2world.buildingtiler.domain.BuildingFeature;
import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.domain.RoofMode;
import org.osm2world.buildingtiler.support.TestBuildingFactory;

class StableStyleHashTest {

	private final StableStyleHash hashes = new StableStyleHash();

	@Test
	void canonicalKeyIgnoresSourceIdRingStartDirectionAndTinyFormatNoise() {
		BuildingFeature geojson = TestBuildingFactory.rectangle("geojson.1", 116.6, 39.9,
				0.001, 0.0005, 18, Map.of("Elevation", 18));
		var reversed = TestBuildingFactory.polygon(new Coordinate[] {
				new Coordinate(116.60100000001, 39.90050000001),
				new Coordinate(116.60100000001, 39.90000000001),
				new Coordinate(116.60000000001, 39.90000000001),
				new Coordinate(116.60000000001, 39.90050000001),
				new Coordinate(116.60100000001, 39.90050000001)
		});
		BuildingFeature shp = new BuildingFeature("shp.77", reversed, 18, Map.of("Elevation", 18));
		assertEquals(hashes.featureKey(geojson), hashes.featureKey(shp));
		assertEquals(hashes.variantBucket(geojson, ModelingConfig.defaults(), 256),
				hashes.variantBucket(shp, ModelingConfig.defaults(), 256));
	}

	@Test
	void configurationParticipatesInStyleHash() {
		var feature = TestBuildingFactory.rectangle("a", 116.6, 39.9, 0.001, 0.0005, 18);
		assertNotEquals(hashes.variantBucket(feature, ModelingConfig.defaults(), Integer.MAX_VALUE),
				hashes.variantBucket(feature,
						ModelingConfig.defaults().withRoofMode(RoofMode.CONSERVATIVE), Integer.MAX_VALUE));
	}

	@Test
	void renderingLodAndSampleSizeDoNotChangeStyleButRemainPartOfConfigurationIdentity() {
		var feature = TestBuildingFactory.rectangle("a", 116.6, 39.9, 0.001, 0.0005, 18);
		var base = ModelingConfig.defaults();
		var renderingOnlyChange = base.withLod(1).withPreviewSampleSize(50);
		assertEquals(hashes.variantBucket(feature, base, Integer.MAX_VALUE),
				hashes.variantBucket(feature, renderingOnlyChange, Integer.MAX_VALUE));
		assertEquals(hashes.outputHash(feature, base, "same-style"),
				hashes.outputHash(feature, renderingOnlyChange, "same-style"));
		assertNotEquals(hashes.configHash(base), hashes.configHash(renderingOnlyChange));
	}

	@Test
	void outputIsIndependentOfThreadAndInputOrder() throws Exception {
		List<BuildingFeature> features = new ArrayList<>();
		for (int i = 0; i < 80; i++) features.add(TestBuildingFactory.rectangle("b" + i,
				116.6 + i * 0.00001, 39.9, 0.000008, 0.000006, 8 + i));
		List<String> expected = features.stream().map(hashes::featureKey).sorted().toList();
		Collections.reverse(features);
		var executor = Executors.newFixedThreadPool(6);
		try {
			List<Callable<String>> tasks = features.stream().<Callable<String>>map(feature -> () -> hashes.featureKey(feature)).toList();
			List<String> actual = executor.invokeAll(tasks).stream().map(future -> {
				try { return future.get(); }
				catch (Exception exception) { throw new IllegalStateException(exception); }
			}).sorted().toList();
			assertEquals(expected, actual);
		} finally {
			executor.shutdownNow();
		}
	}
}
