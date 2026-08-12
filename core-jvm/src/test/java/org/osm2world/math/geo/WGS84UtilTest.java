package org.osm2world.math.geo;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.osm2world.math.geo.WGS84Util.ecefFromLatLon;
import static org.osm2world.math.geo.WGS84Util.eastNorthUpToEcefMatrix;

import java.util.List;

import org.junit.Test;
import org.osm2world.math.VectorXYZ;

public class WGS84UtilTest {

	/** semi-major and semi-minor axis of the WGS84 ellipsoid */
	private static final double A = 6378137.0, B = 6356752.314245179;

	private static final double DELTA = 1e-6;

	private static final List<LatLon> TEST_POSITIONS = asList(
			new LatLon(0, 0), new LatLon(48.14, 11.58), new LatLon(-33.87, 151.21),
			new LatLon(80, -170), new LatLon(-55, 33), new LatLon(0, 180));

	@Test
	public void testEcefFromLatLonKnownPoints() {

		assertAlmostEquals(new VectorXYZ(A, 0, 0), ecefFromLatLon(new LatLon(0, 0), 0));
		assertAlmostEquals(new VectorXYZ(0, A, 0), ecefFromLatLon(new LatLon(0, 90), 0));
		assertAlmostEquals(new VectorXYZ(0, 0, B), ecefFromLatLon(new LatLon(90, 0), 0));
		assertAlmostEquals(new VectorXYZ(0, 0, -B), ecefFromLatLon(new LatLon(-90, 123), 0));

		assertAlmostEquals(new VectorXYZ(A + 100, 0, 0), ecefFromLatLon(new LatLon(0, 0), 100));

	}

	/** points with an elevation of 0 must be located on the surface of the ellipsoid */
	@Test
	public void testEcefFromLatLonOnEllipsoid() {

		for (LatLon pos : TEST_POSITIONS) {
			VectorXYZ p = ecefFromLatLon(pos, 0);
			double onEllipsoid = (p.x * p.x + p.y * p.y) / (A * A) + p.z * p.z / (B * B);
			assertEquals("position " + pos, 1.0, onEllipsoid, 1e-12);
		}

	}

	@Test
	public void testEastNorthUpToEcefMatrixKnownPoint() {

		double[] m = eastNorthUpToEcefMatrix(new LatLon(0, 0), 0);

		assertArrayAlmostEquals(new double[] {
				0, 1, 0, 0,
				0, 0, 1, 0,
				1, 0, 0, 0,
				A, 0, 0, 1
		}, m);

	}

	@Test
	public void testEastNorthUpToEcefMatrixAxes() {

		for (LatLon pos : TEST_POSITIONS) {

			double[] m = eastNorthUpToEcefMatrix(pos, 0);

			VectorXYZ east = new VectorXYZ(m[0], m[1], m[2]);
			VectorXYZ north = new VectorXYZ(m[4], m[5], m[6]);
			VectorXYZ up = new VectorXYZ(m[8], m[9], m[10]);

			/* the axes must form a right-handed orthonormal basis */

			assertEquals(1.0, east.length(), DELTA);
			assertEquals(1.0, north.length(), DELTA);
			assertEquals(1.0, up.length(), DELTA);

			assertEquals(0.0, east.dot(north), DELTA);
			assertEquals(0.0, north.dot(up), DELTA);
			assertEquals(0.0, up.dot(east), DELTA);

			assertAlmostEquals(up, east.cross(north));

			/* the fourth column must be the origin, and the last row must be that of an affine transformation */

			assertAlmostEquals(ecefFromLatLon(pos, 0), new VectorXYZ(m[12], m[13], m[14]));
			assertArrayAlmostEquals(new double[] {0, 0, 0, 1}, new double[] {m[3], m[7], m[11], m[15]});

			/* moving along the up axis must be equivalent to increasing the elevation */

			assertAlmostEquals(ecefFromLatLon(pos, 250), ecefFromLatLon(pos, 0).add(up.mult(250)));

		}

	}

	private static void assertAlmostEquals(VectorXYZ expected, VectorXYZ actual) {
		assertEquals("expected " + expected + ", was " + actual, 0, expected.distanceTo(actual), DELTA);
	}

	private static void assertArrayAlmostEquals(double[] expected, double[] actual) {
		assertEquals(expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			assertEquals("element " + i, expected[i], actual[i], DELTA);
		}
	}

}
