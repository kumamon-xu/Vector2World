package org.osm2world.buildingtiler.osm2world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.osm2world.math.geo.LatLon;
import org.osm2world.math.geo.MetricMapProjection;

class MetricProjectionTest {

	@Test
	void roundTripsWgs84CoordinatesWithinSubMillimeterAngularTolerance() {
		LatLon origin = new LatLon(39.87, 116.67);
		MetricMapProjection projection = new MetricMapProjection(origin);

		LatLon restored = projection.toLatLon(projection.toXZ(39.901234, 116.612345));

		assertEquals(39.901234, restored.lat, 1e-9);
		assertEquals(116.612345, restored.lon, 1e-9);
	}

}
