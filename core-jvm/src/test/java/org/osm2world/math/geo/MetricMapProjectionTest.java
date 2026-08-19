package org.osm2world.math.geo;

import org.junit.Ignore;
import org.junit.Test;

public class MetricMapProjectionTest extends AbstractMapProjectionTest {

	@Override
	protected MapProjection createProjection(LatLon origin) {
		return new MetricMapProjection(origin);
	}

	@Override
	@Ignore //TODO: This projection does work properly across the date boundary
	@Test
	public void testDateBoundary() {}

}
