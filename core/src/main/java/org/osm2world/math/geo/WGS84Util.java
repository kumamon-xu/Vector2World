package org.osm2world.math.geo;

import static java.lang.Math.*;

import org.osm2world.math.VectorXYZ;

/**
 * Utility class for the WGS84 reference ellipsoid and Earth-centered, Earth-fixed (ECEF) coordinates.
 */
public final class WGS84Util {

	/** semi-major axis (equatorial radius) of the WGS84 ellipsoid in meters */
	private static final double SEMI_MAJOR_AXIS = 6_378_137.0;

	/** inverse of the WGS84 ellipsoid's flattening */
	private static final double INVERSE_FLATTENING = 298.257223563;

	/** square of the ellipsoid's first eccentricity, i.e. 1 - (b/a)² */
	private static final double ECCENTRICITY_SQ = (2 - 1 / INVERSE_FLATTENING) / INVERSE_FLATTENING;

	private WGS84Util() {}

	/**
	 * converts geodetic coordinates to ECEF coordinates.
	 *
	 * @param pos  latitude and longitude
	 * @param ele  height above the ellipsoid in meters
	 *
	 * @return ECEF coordinates. Represented as {@link VectorXYZ}, but the axes have a different meaning than
	 * elsewhere in OSM2World: x points at (0°N, 0°E), y at (0°N, 90°E), and z at the North Pole.
	 * All distances are in meters.
	 */
	static VectorXYZ ecefFromLatLon(LatLon pos, double ele) {

		double sinLat = sin(toRadians(pos.lat)), cosLat = cos(toRadians(pos.lat));
		double sinLon = sin(toRadians(pos.lon)), cosLon = cos(toRadians(pos.lon));

		/* radius of curvature in the prime vertical */
		double n = SEMI_MAJOR_AXIS / sqrt(1 - ECCENTRICITY_SQ * sinLat * sinLat);

		return new VectorXYZ(
				(n + ele) * cosLat * cosLon,
				(n + ele) * cosLat * sinLon,
				(n * (1 - ECCENTRICITY_SQ) + ele) * sinLat);

	}

	/**
	 * builds the transformation from a local east-north-up (ENU) coordinate system to ECEF coordinates.
	 * The ENU system has its origin at the given position, with its axes pointing east, north,
	 * and up along the ellipsoid's surface normal.
	 *
	 * @param origin  latitude and longitude of the ENU system's origin
	 * @param ele  height of the ENU system's origin above the ellipsoid in meters
	 *
	 * @return  4x4 matrix in column-major order (the layout expected by glTF and 3D Tiles):
	 *          the east, north and up axes, followed by the origin's ECEF coordinates
	 */
	public static double[] eastNorthUpToEcefMatrix(LatLon origin, double ele) {

		double sinLat = sin(toRadians(origin.lat)), cosLat = cos(toRadians(origin.lat));
		double sinLon = sin(toRadians(origin.lon)), cosLon = cos(toRadians(origin.lon));

		VectorXYZ pos = ecefFromLatLon(origin, ele);

		return new double[] {
				-sinLon,          cosLon,           0,      0,  // east
				-sinLat * cosLon, -sinLat * sinLon, cosLat, 0,  // north
				cosLat * cosLon,  cosLat * sinLon,  sinLat, 0,  // up
				pos.x,            pos.y,            pos.z,  1   // origin
		};

	}

}
