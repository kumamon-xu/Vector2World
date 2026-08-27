package org.osm2world.buildingtiler.gis;

import java.util.Locale;

import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

final class CrsSupport {

	private static final CoordinateReferenceSystem WGS84 = decodeWgs84();

	private CrsSupport() {}

	static ResolvedCrs resolve(String declaredCrs, String explicitOverride) throws DatasetImportException {
		String selected = explicitOverride != null && !explicitOverride.isBlank() ? explicitOverride : declaredCrs;
		if (selected == null || selected.isBlank()) {
			throw new DatasetImportException(DatasetErrorCode.CRS_REQUIRED,
					"Source CRS is missing; provide an explicit CRS such as EPSG:4326");
		}
		try {
			CoordinateReferenceSystem crs = decode(selected);
			return resolve(crs, selected, explicitOverride != null && !explicitOverride.isBlank());
		} catch (DatasetImportException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new DatasetImportException(DatasetErrorCode.CRS_UNRESOLVED,
					"Could not resolve source CRS: " + selected, exception);
		}
	}

	static ResolvedCrs resolve(CoordinateReferenceSystem declaredCrs, String explicitOverride)
			throws DatasetImportException {
		if (explicitOverride != null && !explicitOverride.isBlank()) return resolve((String) null, explicitOverride);
		if (declaredCrs == null) {
			throw new DatasetImportException(DatasetErrorCode.CRS_REQUIRED,
					"Shapefile .prj is missing or unreadable; provide an explicit CRS");
		}
		String name = canonicalName(declaredCrs);
		return resolve(declaredCrs, name, false);
	}

	private static ResolvedCrs resolve(CoordinateReferenceSystem source, String name, boolean overridden)
			throws DatasetImportException {
		try {
			MathTransform transform = CRS.findMathTransform(source, WGS84, true);
			return new ResolvedCrs(source, name, transform, overridden);
		} catch (Exception exception) {
			throw new DatasetImportException(DatasetErrorCode.CRS_TRANSFORM_FAILED,
					"Could not create a longitude-first WGS84 transform for " + name, exception);
		}
	}

	static Geometry toWgs84(Geometry geometry, ResolvedCrs crs) throws DatasetImportException {
		try {
			Geometry transformed = crs.transform().isIdentity() ? geometry.copy() : JTS.transform(geometry, crs.transform());
			for (Coordinate coordinate : transformed.getCoordinates()) {
				if (!Double.isFinite(coordinate.x) || !Double.isFinite(coordinate.y)
						|| coordinate.x < -180 || coordinate.x > 180
						|| coordinate.y < -90 || coordinate.y > 90) {
					throw new DatasetImportException(DatasetErrorCode.COORDINATE_OUT_OF_RANGE,
							"Transformed coordinate is outside longitude/latitude range: "
									+ coordinate.x + ", " + coordinate.y);
				}
			}
			transformed.setSRID(4326);
			return transformed;
		} catch (DatasetImportException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new DatasetImportException(DatasetErrorCode.CRS_TRANSFORM_FAILED,
					"Coordinate transform failed for " + crs.name(), exception);
		}
	}

	private static CoordinateReferenceSystem decode(String definition) throws Exception {
		String normalized = definition.trim();
		String upper = normalized.toUpperCase(Locale.ROOT);
		if (upper.contains("CRS84")) return CRS.decode("EPSG:4326", true);
		if (upper.startsWith("EPSG:") || upper.matches("[0-9]{4,6}")) {
			String code = upper.startsWith("EPSG:") ? upper : "EPSG:" + upper;
			return CRS.decode(code, true);
		}
		if (upper.contains("GEOGCS[") || upper.contains("PROJCS[")
				|| upper.contains("GEODCRS[") || upper.contains("PROJCRS[")) {
			return CRS.parseWKT(normalized);
		}
		return CRS.decode(normalized, true);
	}

	private static CoordinateReferenceSystem decodeWgs84() {
		try { return CRS.decode("EPSG:4326", true); }
		catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
	}

	private static String canonicalName(CoordinateReferenceSystem crs) {
		try {
			String identifier = CRS.toSRS(crs, true);
			if (identifier != null && !identifier.isBlank()) return identifier.contains(":")
					? identifier : "EPSG:" + identifier;
		} catch (RuntimeException ignored) {
			// Fall back to the human-readable CRS name below.
		}
		return crs.getName().toString();
	}

	record ResolvedCrs(CoordinateReferenceSystem source, String name,
			MathTransform transform, boolean overridden) {}
}
