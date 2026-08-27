package org.osm2world.buildingtiler.gis;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.util.GeometryFixer;

final class GeometryNormalizer {

	private GeometryNormalizer() {}

	static Result normalize(Geometry geometry, ImportOptions options) {
		if (geometry == null || geometry.isEmpty()) return Result.rejected("EMPTY_GEOMETRY", "Geometry is null or empty");
		double originalArea = polygonalArea(geometry);
		Geometry fixed;
		try {
			fixed = GeometryFixer.fix(geometry);
		} catch (RuntimeException exception) {
			return Result.rejected("GEOMETRY_REPAIR_FAILED", "GeometryFixer failed: " + exception.getMessage());
		}
		Geometry polygonal = extractPolygonal(fixed);
		if (polygonal == null || polygonal.isEmpty() || polygonal.getArea() <= 0 || !polygonal.isValid()) {
			return Result.rejected("INVALID_POLYGON", "Geometry has no valid positive-area polygon after repair");
		}

		double ratio = originalArea <= 0 ? 0.0
				: Math.abs(polygonal.getArea() - originalArea) / originalArea;
		if (ratio > options.repairRejectAreaRatio()) {
			return Result.rejected("REPAIR_AREA_CHANGE_EXCESSIVE",
					"Geometry repair changed polygonal area by " + Math.round(ratio * 1000) / 10.0 + "%");
		}
		Geometry normalized = polygonal.copy();
		normalized.normalize();
		normalized.setSRID(4326);
		boolean repaired = !geometry.isValid() || geometry.getNumPoints() != polygonal.getNumPoints()
				|| (geometry instanceof GeometryCollection && !(geometry instanceof MultiPolygon))
				|| !geometry.equalsExact(polygonal);
		String warning = ratio > options.repairWarningAreaRatio()
				? "Geometry repair changed polygonal area by " + Math.round(ratio * 1000) / 10.0 + "%"
				: (!geometry.isValid() ? "Invalid geometry was repaired without a reliable original-area baseline" : null);
		return new Result(normalized, repaired, warning, null, null);
	}

	private static double polygonalArea(Geometry geometry) {
		Geometry polygonal = extractPolygonal(geometry);
		return polygonal == null ? 0 : polygonal.getArea();
	}

	private static Geometry extractPolygonal(Geometry geometry) {
		List<Polygon> polygons = new ArrayList<>();
		collectPolygons(geometry, polygons);
		if (polygons.isEmpty()) return null;
		if (polygons.size() == 1) return polygons.get(0);
		GeometryFactory factory = polygons.get(0).getFactory();
		return factory.createMultiPolygon(polygons.toArray(Polygon[]::new));
	}

	private static void collectPolygons(Geometry geometry, List<Polygon> polygons) {
		if (geometry instanceof Polygon polygon) {
			polygons.add(polygon);
		} else if (geometry instanceof MultiPolygon || geometry instanceof GeometryCollection) {
			for (int i = 0; i < geometry.getNumGeometries(); i++) collectPolygons(geometry.getGeometryN(i), polygons);
		}
	}

	record Result(Geometry geometry, boolean repaired, String warning, String rejectionCode, String rejectionMessage) {
		static Result rejected(String code, String message) { return new Result(null, false, null, code, message); }
		boolean accepted() { return geometry != null; }
	}
}
