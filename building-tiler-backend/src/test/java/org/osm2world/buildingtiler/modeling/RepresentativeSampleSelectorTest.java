package org.osm2world.buildingtiler.modeling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.osm2world.buildingtiler.domain.BuildingFeature;
import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.support.TestBuildingFactory;

class RepresentativeSampleSelectorTest {

	@Test
	void selectsBoundedStratifiedSampleIndependentOfInputOrder() {
		List<BuildingFeature> features = new ArrayList<>();
		for (int i = 0; i < 75; i++) {
			double height = i < 25 ? 8 + i * 0.1 : i < 50 ? 20 + i * 0.2 : 55 + i;
			double size = i % 10 == 0 ? 0.003 : 0.0002;
			features.add(TestBuildingFactory.rectangle("b" + i, 116.5 + i * 0.0001, 39.8,
					size, size / 2, height));
		}
		features.add(new BuildingFeature("irregular", TestBuildingFactory.polygon(new Coordinate[] {
				new Coordinate(116.7, 39.9), new Coordinate(116.702, 39.9),
				new Coordinate(116.702, 39.9003), new Coordinate(116.7003, 39.9003),
				new Coordinate(116.7003, 39.902), new Coordinate(116.7, 39.902),
				new Coordinate(116.7, 39.9)
		}), 18, java.util.Map.of()));
		ModelingConfig config = ModelingConfig.defaults().withPreviewSampleSize(50);
		RepresentativeSampleSelector selector = new RepresentativeSampleSelector();
		var first = selector.select(features, config);
		Collections.reverse(features);
		var second = selector.select(features, config);
		assertEquals(50, first.features().size());
		assertEquals(first.features().stream().map(BuildingFeature::id).toList(),
				second.features().stream().map(BuildingFeature::id).toList());
		assertEquals(first.selectionHash(), second.selectionHash());
		for (var bucket : RepresentativeSampleSelector.Bucket.values()) {
			assertFalse(first.bucketCoverage().get(bucket.name()).isEmpty(), bucket::name);
		}
	}

	@Test
	void returnsAllBuildingsWhenDatasetIsSmallerThanConfiguredSample() {
		List<BuildingFeature> features = List.of(
				TestBuildingFactory.rectangle("a", 116.6, 39.9, .001, .001, 10),
				TestBuildingFactory.rectangle("b", 116.7, 39.9, .001, .001, 60));
		assertEquals(2, new RepresentativeSampleSelector().select(features, ModelingConfig.defaults()).features().size());
	}
}
