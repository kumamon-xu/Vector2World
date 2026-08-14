package org.osm2world.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ResolutionTest {

	@Test
	public void testAspectRatio() {

		var r1 = new Resolution(1000, 500);
		var r2 = new Resolution("256x256");
		var r3 = new Resolution("64,128");

		assertEquals(2.0f, r1.getAspectRatio(), 0f);
		assertEquals(1.0f, r2.getAspectRatio(), 0f);
		assertEquals(0.5f, r3.getAspectRatio(), 0f);

	}

	@Test
	public void testScale() {

		var r512 = new Resolution("512x512");
		var r2048 = new Resolution(2048, 2048);

		assertEquals(r512, r2048.scale(0.25));
		assertEquals(r2048, r512.scale(4.0));

	}

}
