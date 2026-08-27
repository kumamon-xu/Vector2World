package org.osm2world.buildingtiler.modeling;

import static java.lang.Math.PI;

import org.locationtech.jts.algorithm.MinimumDiameter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.osm2world.buildingtiler.domain.FootprintMetrics;
import org.osm2world.buildingtiler.domain.FootprintThresholds;

public final class FootprintAnalyzer {

	private static final double EARTH_RADIUS_METERS = 6_378_137.0;
	private static final double QUANTIZATION_METERS = 0.01;
	private static final double RIGHT_ANGLE_TOLERANCE = Math.toRadians(10.0);

	public FootprintMetrics analyze(Geometry footprintWgs84, FootprintThresholds thresholds) {
		if (!(footprintWgs84 instanceof Polygon || footprintWgs84 instanceof MultiPolygon)
				|| footprintWgs84.isEmpty()) {
			throw new IllegalArgumentException("Footprint must be a non-empty Polygon or MultiPolygon");
		}
		if (thresholds == null) thresholds = FootprintThresholds.defaults();
		Geometry metric = toLocalMetric(footprintWgs84);
		double area = metric.getArea();
		double perimeter = metric.getLength();
		if (!(area > 0) || !(perimeter > 0)) throw new IllegalArgumentException("Footprint has no measurable area");

		double compactness = clamp(4.0 * PI * area / (perimeter * perimeter));
		double hullArea = metric.convexHull().getArea();
		double convexity = hullArea > 0 ? clamp(area / hullArea) : 0;
		int vertexCount = vertexCount(metric);
		double aspectRatio = aspectRatio(metric);
		double orthogonality = orthogonality(metric);
		int partCount = metric instanceof MultiPolygon multi ? multi.getNumGeometries() : 1;
		boolean irregular = partCount > 1
				|| compactness < thresholds.minimumCompactness()
				|| convexity < thresholds.minimumConvexity()
				|| orthogonality < thresholds.minimumOrthogonality()
				|| vertexCount > thresholds.maximumSimpleVertices()
				|| aspectRatio > thresholds.maximumPitchedAspectRatio();

		return new FootprintMetrics(area, perimeter, compactness, convexity, vertexCount,
				aspectRatio, orthogonality, partCount, irregular);
	}

	private static Geometry toLocalMetric(Geometry source) {
		Coordinate center = source.getCentroid().getCoordinate();
		double originLonRadians = Math.toRadians(center.x);
		double originLatRadians = Math.toRadians(center.y);
		double cosLatitude = Math.cos(originLatRadians);
		Geometry result = source.copy();
		result.apply((CoordinateFilter) coordinate -> {
			double longitude = Math.toRadians(coordinate.x);
			double latitude = Math.toRadians(coordinate.y);
			coordinate.x = quantize((longitude - originLonRadians) * cosLatitude * EARTH_RADIUS_METERS);
			coordinate.y = quantize((latitude - originLatRadians) * EARTH_RADIUS_METERS);
		});
		result.geometryChanged();
		return result;
	}

	private static int vertexCount(Geometry geometry) {
		int count = 0;
		for (int i = 0; i < geometry.getNumGeometries(); i++) {
			Polygon polygon = (Polygon)geometry.getGeometryN(i);
			count += polygon.getExteriorRing().getNumPoints() - 1;
			for (int hole = 0; hole < polygon.getNumInteriorRing(); hole++) {
				count += polygon.getInteriorRingN(hole).getNumPoints() - 1;
			}
		}
		return count;
	}

	private static double aspectRatio(Geometry geometry) {
		Coordinate[] rectangle = new MinimumDiameter(geometry).getMinimumRectangle().getCoordinates();
		if (rectangle.length < 4) return 1;
		double minimum = Double.POSITIVE_INFINITY;
		double maximum = 0;
		for (int i = 1; i < rectangle.length; i++) {
			double length = rectangle[i - 1].distance(rectangle[i]);
			if (length > 1e-9) {
				minimum = Math.min(minimum, length);
				maximum = Math.max(maximum, length);
			}
		}
		return Double.isFinite(minimum) && minimum > 0 ? maximum / minimum : 1;
	}

	private static double orthogonality(Geometry geometry) {
		int corners = 0;
		int rightAngles = 0;
		for (int i = 0; i < geometry.getNumGeometries(); i++) {
			Coordinate[] ring = ((Polygon)geometry.getGeometryN(i)).getExteriorRing().getCoordinates();
			int size = ring.length - 1;
			for (int vertex = 0; vertex < size; vertex++) {
				Coordinate previous = ring[(vertex - 1 + size) % size];
				Coordinate current = ring[vertex];
				Coordinate next = ring[(vertex + 1) % size];
				double ax = previous.x - current.x;
				double ay = previous.y - current.y;
				double bx = next.x - current.x;
				double by = next.y - current.y;
				double denominator = Math.hypot(ax, ay) * Math.hypot(bx, by);
				if (denominator <= 1e-9) continue;
				double angle = Math.acos(Math.max(-1, Math.min(1, (ax * bx + ay * by) / denominator)));
				corners++;
				if (Math.abs(angle - PI / 2.0) <= RIGHT_ANGLE_TOLERANCE) rightAngles++;
			}
		}
		return corners == 0 ? 0 : (double)rightAngles / corners;
	}

	private static double quantize(double value) {
		return Math.rint(value / QUANTIZATION_METERS) * QUANTIZATION_METERS;
	}

	private static double clamp(double value) {
		return Math.max(0, Math.min(1, value));
	}
}
