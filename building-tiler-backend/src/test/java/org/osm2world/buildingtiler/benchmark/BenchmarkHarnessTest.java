package org.osm2world.buildingtiler.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.domain.HeightUnit;
import org.osm2world.buildingtiler.domain.InvalidHeightPolicy;
import org.osm2world.buildingtiler.gis.GeoJsonDatasetReader;
import org.osm2world.buildingtiler.gis.ImportOptions;

import com.google.gson.JsonParser;

class BenchmarkHarnessTest {

	@TempDir Path temporary;

	@Test
	void corpusIsDeterministicAndCarriesAllRequiredScenarios() throws Exception {
		BenchmarkCorpusGenerator generator = new BenchmarkCorpusGenerator();
		var first = generator.generate(temporary.resolve("中文-基准-"
				+ "long-path-segment-".repeat(6)), 1_000);
		var second = generator.generate(temporary.resolve("second"), 1_000);
		assertEquals(first.sha256(), second.sha256());
		assertEquals(1_000, first.scenarioCounts().values().stream().mapToInt(Integer::intValue).sum());
		assertTrue(first.scenarioCounts().keySet().containsAll(
				java.util.Set.of("simple", "complex", "holes", "multipolygon", "cross-tile",
						"wide-attributes")));
		var inspection = new GeoJsonDatasetReader().inspect(first.geoJson(),
				ImportOptions.defaults().withExplicitCrs("OGC:CRS84"));
		var result = inspection.materialize(new HeightMapping("Elevation", HeightUnit.M,
				InvalidHeightPolicy.FAIL, 10_000));
		assertEquals(1_000, result.buildings().size());
		assertTrue(Files.readString(first.manifest()).contains("CC0-1.0"));
	}

	@Test
	void relativeGateDetectsInjectedRegressionAndRecordsExplicitExemption() {
		var baseline = JsonParser.parseString(report(100, 1_000, 10_000, 1_000)).getAsJsonObject();
		var regressed = JsonParser.parseString(report(300, 2_000, 20_000, 1_000)).getAsJsonObject();
		BenchmarkRegressionGate gate = new BenchmarkRegressionGate();
		var failure = gate.evaluate(baseline, regressed, null);
		assertFalse(failure.passed());
		assertTrue(failure.violations().stream().anyMatch(value -> value.contains("medianTotalMillis")));
		var exempted = gate.evaluate(baseline, regressed, "approved algorithm change V2W-123");
		assertTrue(exempted.passed());
		assertTrue(exempted.exempted());
		assertEquals("approved algorithm change V2W-123", exempted.exemptionReason());
	}

	private static String report(long millis, long heap, long output, int successfulRuns) {
		return "{\"summary\":[{\"featureCount\":1000,\"successfulRuns\":" + successfulRuns
				+ ",\"medianTotalMillis\":" + millis + ",\"medianPeakHeapBytes\":" + heap
				+ ",\"medianOutputBytes\":" + output + "}]}";
	}
}
