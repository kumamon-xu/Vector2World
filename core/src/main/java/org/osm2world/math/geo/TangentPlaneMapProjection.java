package org.osm2world.math.geo;

import static java.lang.Math.*;

import java.util.Objects;

import org.osm2world.math.VectorXYZ;
import org.osm2world.math.VectorXZ;

/**
 * Projection onto the plane which touches the WGS84 ellipsoid at the origin, with the x axis pointing east
 * and the z axis pointing north. Its results are coordinates in the local east-north-up (ENU) system whose
 * transform to ECEF is built by {@link WGS84Util#eastNorthUpToEcefMatrix(LatLon, double)}, which makes it the
 * projection to use for output formats that position their content with such a transform, such as 3D Tiles.
 * Accuracy degrades with the distance from the origin, so like other {@link MapProjection}s in OSM2World,
 * this is only suitable for data covering a relatively small part of the globe.
 */
public class TangentPlaneMapProjection implements MapProjection {

	private final LatLon origin;

	/** ECEF coordinates of the {@link #origin} */
	private final VectorXYZ originEcef;

	/** ECEF directions of the tangent plane's axes, and of the plane's normal */
	private final VectorXYZ east, north, up;

	public TangentPlaneMapProjection(LatLon origin) {

		this.origin = origin;
		this.originEcef = WGS84Util.ecefFromLatLon(origin, 0);

		double sinLat = sin(toRadians(origin.lat)), cosLat = cos(toRadians(origin.lat));
		double sinLon = sin(toRadians(origin.lon)), cosLon = cos(toRadians(origin.lon));

		this.east = new VectorXYZ(-sinLon, cosLon, 0);
		this.north = new VectorXYZ(-sinLat * cosLon, -sinLat * sinLon, cosLat);
		this.up = new VectorXYZ(cosLat * cosLon, cosLat * sinLon, sinLat);

	}

	@Override
	public LatLon getOrigin() {
		return origin;
	}

	@Override
	public VectorXZ toXZ(double lat, double lon) {

		VectorXYZ offset = WGS84Util.ecefFromLatLon(new LatLon(lat, lon), 0).subtract(originEcef);

		/* the component along the plane's normal is dropped; it is what makes this a projection */

		double x = offset.dot(east);
		double z = offset.dot(north);

		/* snap to mm precision, seems to reduce geometry exceptions */
		x = Math.round(x * 1000) / 1000.0d;
		z = Math.round(z * 1000) / 1000.0d;

		return new VectorXZ(x, z);

	}

	@Override
	public LatLon toLatLon(VectorXZ pos) {

		VectorXYZ pointOnPlane = originEcef.add(east.mult(pos.x)).add(north.mult(pos.z));

		/* invert the projection by going back down to the surface along the plane's normal */

		return WGS84Util.latLonFromEcef(WGS84Util.intersectWithSurface(pointOnPlane, up.invert()));

	}

	@Override
	public double toLat(VectorXZ pos) {
		return toLatLon(pos).lat;
	}

	@Override
	public double toLon(VectorXZ pos) {
		return toLatLon(pos).lon;
	}

	@Override
	public String toString() {
		return "TangentPlaneMapProjection" + origin;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof TangentPlaneMapProjection other && Objects.equals(origin, other.origin);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(origin);
	}

}
