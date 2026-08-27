package org.osm2world.buildingtiler.modeling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.osm2world.buildingtiler.domain.FootprintThresholds;
import org.osm2world.buildingtiler.support.TestBuildingFactory;

class FootprintAnalyzerTest {

	private final FootprintAnalyzer analyzer = new FootprintAnalyzer();

	@Test
	void rectangleMetricsIgnoreRingStartDirectionAndSubCentimeterNoise() {
		var first = TestBuildingFactory.polygon(new Coordinate[] {
				new Coordinate(116.6, 39.9), new Coordinate(116.601, 39.9),
				new Coordinate(116.601, 39.9005), new Coordinate(116.6, 39.9005),
				new Coordinate(116.6, 39.9)
		});
		var reordered = TestBuildingFactory.polygon(new Coordinate[] {
				new Coordinate(116.60100000001, 39.90050000001),
				new Coordinate(116.60100000001, 39.90000000001),
				new Coordinate(116.60000000001, 39.90000000001),
				new Coordinate(116.60000000001, 39.90050000001),
				new Coordinate(116.60100000001, 39.90050000001)
		});
		var a = analyzer.analyze(first, FootprintThresholds.defaults());
		var b = analyzer.analyze(reordered, FootprintThresholds.defaults());
		assertEquals(a.areaSquareMeters(), b.areaSquareMeters(), 0.02);
		assertEquals(a.compactness(), b.compactness(), 1e-6);
		assertEquals(a.aspectRatio(), b.aspectRatio(), 1e-6);
		assertEquals(1.0, a.orthogonality());
		assertFalse(a.irregular());
	}

	@Test
	void concaveAndMultipartFootprintsAreExplicitlyIrregular() {
		var lShape = TestBuildingFactory.polygon(new Coordinate[] {
				new Coordinate(116.6, 39.9), new Coordinate(116.602, 39.9),
				new Coordinate(116.602, 39.9004), new Coordinate(116.6004, 39.9004),
				new Coordinate(116.6004, 39.902), new Coordinate(116.6, 39.902),
				new Coordinate(116.6, 39.9)
		});
		var second = (org.locationtech.jts.geom.Polygon)lShape.copy();
		second.apply((org.locationtech.jts.geom.CoordinateFilter) value -> value.x += 0.01);
		second.geometryChanged();
		var multi = TestBuildingFactory.geometryFactory().createMultiPolygon(
				new org.locationtech.jts.geom.Polygon[] { lShape, second });
		assertTrue(analyzer.analyze(lShape, FootprintThresholds.defaults()).irregular());
		assertTrue(analyzer.analyze(multi, FootprintThresholds.defaults()).irregular());
		assertEquals(2, analyzer.analyze(multi, FootprintThresholds.defaults()).partCount());
	}

	@Test
	void metricsAreInvariantWhenTheSameFootprintIsRotated() {
		Geometry axisAligned = TestBuildingFactory.rectangle("axis", 0, 0,
				0.001, 0.0005, 12).geometryWgs84();
		Geometry rotated = AffineTransformation.rotationInstance(Math.toRadians(37), 0.0005, 0.00025)
				.transform(axisAligned);
		var a = analyzer.analyze(axisAligned, FootprintThresholds.defaults());
		var b = analyzer.analyze(rotated, FootprintThresholds.defaults());
		assertEquals(a.areaSquareMeters(), b.areaSquareMeters(), 1.0);
		assertEquals(a.compactness(), b.compactness(), 1e-4);
		assertEquals(a.aspectRatio(), b.aspectRatio(), 1e-3);
		assertEquals(a.orthogonality(), b.orthogonality(), 1e-9);
		assertEquals(a.irregular(), b.irregular());
	}
}
