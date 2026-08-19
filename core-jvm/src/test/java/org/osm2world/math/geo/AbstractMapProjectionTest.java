package org.osm2world.math.geo;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.osm2world.test.TestUtil.assertAlmostEquals;

import java.util.List;

import org.junit.Test;
import org.osm2world.math.VectorXZ;

public abstract class AbstractMapProjectionTest {

	abstract protected MapProjection createProjection(LatLon origin);

	/** precision expected for {@link LatLon} results; different from that for XYZ coords */
	protected static final double DELTA = 1e-6;

	@Test
	public void testOriginAndAxes() {

		List<LatLon> origins = asList(new LatLon(0, 0), new LatLon(80, -170), new LatLon(-55, 33));

		for (LatLon origin : origins) {

			MapProjection proj = createProjection(origin);

			assertAlmostEquals(0, 0, proj.toXZ(origin));
			assertEquals(origin.lat, proj.toLat(new VectorXZ(0, 0)), DELTA);
			assertEquals(origin.lon, proj.toLon(new VectorXZ(0, 0)), DELTA);

			VectorXZ northPoint = proj.toXZ(origin).add(0, 1);
			assertTrue(origin.lat < proj.toLat(northPoint));
			assertEquals(origin.lon, proj.toLon(northPoint), DELTA);

			VectorXZ eastPoint = proj.toXZ(origin).add(1, 0);
			assertEquals(origin.lat, proj.toLat(eastPoint), DELTA);
			assertTrue(origin.lon < proj.toLon(eastPoint));

		}

	}

	/** checks that projecting and un-projecting a position returns the original coordinates */
	@Test
	public void testRoundTrip() {

		var origin = new LatLon(48.5732, 13.4623);
		MapProjection proj = createProjection(origin);

		for (LatLon pos : List.of(origin,
				new LatLon(48.5, 13.5),
				new LatLon(48.6789, 13.3456),
				new LatLon(48.9, 13.9))) {

			VectorXZ xz = proj.toXZ(pos);

			assertEquals(pos.lat, proj.toLat(xz), 1e-8);
			assertEquals(pos.lon, proj.toLon(xz), 1e-8);

		}

	}

	@Test
	public void testDateBoundary() {

		MapProjection proj = createProjection(new LatLon(0, 179.999999));

		VectorXZ pointAcrossBoundary = proj.toXZ(new LatLon(0, -179.999999));
		assertEquals(0, pointAcrossBoundary.z, DELTA);
		assertTrue("point was: " + pointAcrossBoundary, pointAcrossBoundary.x > 0);

	}

}
