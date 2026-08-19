package org.osm2world.math.geo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TangentPlaneMapProjectionTest extends AbstractMapProjectionTest {

	@Override
	protected MapProjection createProjection(LatLon origin) {
		return new TangentPlaneMapProjection(origin);
	}

	/**
	 * checks the projection's scale against distances on the WGS84 ellipsoid.
	 * The expected values are the true lengths of 0.1° of latitude and longitude.
	 */
	@Test
	public void testScale() {

		MapProjection projEquator = createProjection(new LatLon(0, 0));

		assertEquals(11131.9491, projEquator.toXZ(0, 0.1).x, 0.01);
		assertEquals(11057.4277, projEquator.toXZ(0.1, 0).z, 0.01);

		MapProjection proj50 = createProjection(new LatLon(50, 0));

		assertEquals(7169.5754, proj50.toXZ(50, 0.1).x, 0.01);
		assertEquals(11123.0027, proj50.toXZ(50.1, 0).z, 0.01);

	}

}
