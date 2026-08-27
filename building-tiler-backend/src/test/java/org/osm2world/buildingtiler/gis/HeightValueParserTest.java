package org.osm2world.buildingtiler.gis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.domain.HeightUnit;

class HeightValueParserTest {

	@Test
	void convertsAllSupportedUnitsToMeters() {
		assertMeters(12, 12, HeightUnit.M);
		assertMeters(12, 1200, HeightUnit.CM);
		assertMeters(12, 12000, HeightUnit.MM);
		assertMeters(3.048, 10, HeightUnit.FT);
	}

	@Test
	void classifiesEveryInvalidHeightWithoutDefaultReplacement() {
		HeightMapping mapping = new HeightMapping("h", HeightUnit.M);
		assertEquals(HeightValueParser.Status.NULL_OR_EMPTY, HeightValueParser.parse(null, mapping).status());
		assertEquals(HeightValueParser.Status.NULL_OR_EMPTY, HeightValueParser.parse("  ", mapping).status());
		assertEquals(HeightValueParser.Status.NON_NUMERIC, HeightValueParser.parse("12,5", mapping).status());
		assertEquals(HeightValueParser.Status.NON_FINITE, HeightValueParser.parse("NaN", mapping).status());
		assertEquals(HeightValueParser.Status.NON_FINITE, HeightValueParser.parse("Infinity", mapping).status());
		assertEquals(HeightValueParser.Status.NON_POSITIVE, HeightValueParser.parse(0, mapping).status());
		assertEquals(HeightValueParser.Status.NON_POSITIVE, HeightValueParser.parse(-1, mapping).status());
		assertEquals(HeightValueParser.Status.ABOVE_MAXIMUM, HeightValueParser.parse(10001, mapping).status());
	}

	@Test
	void scientificNotationIsLocaleIndependent() {
		Locale previous = Locale.getDefault();
		try {
			Locale.setDefault(Locale.FRANCE);
			assertMeters(125, "1.25e2", HeightUnit.M);
		} finally {
			Locale.setDefault(previous);
		}
	}

	private static void assertMeters(double expected, Object value, HeightUnit unit) {
		var result = HeightValueParser.parse(value, new HeightMapping("h", unit));
		assertEquals(HeightValueParser.Status.VALID, result.status());
		assertEquals(expected, result.meters(), 1e-12);
	}
}
