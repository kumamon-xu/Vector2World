package org.osm2world.buildingtiler.gis;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.util.GeometryFixer;

final class GeometrySupport {

	private GeometrySupport() {}

	static Geometry normalizePolygonal(Geometry geometry) {
		if (geometry == null || geometry.isEmpty()) return null;

		Geometry fixed = GeometryFixer.fix(geometry);
		if (!(fixed instanceof Polygon || fixed instanceof MultiPolygon)
				|| fixed.isEmpty() || !fixed.isValid() || fixed.getArea() <= 0) {
			return null;
		}
		fixed.setSRID(4326);
		return fixed;
	}

	static Double parseHeight(Object value) {
		if (value == null) return null;
		try {
			double parsed = value instanceof Number number
					? number.doubleValue()
					: Double.parseDouble(value.toString().trim());
			return Double.isFinite(parsed) && parsed > 0 ? parsed : null;
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

}
