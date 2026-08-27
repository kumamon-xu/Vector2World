package org.osm2world.buildingtiler.modeling;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.domain.RoofMode;
import org.osm2world.buildingtiler.domain.StylePresetId;
import org.osm2world.buildingtiler.osm2world.Osm2WorldEngineAdapter;
import org.osm2world.buildingtiler.support.TestBuildingFactory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

class M2GoldenSuiteTest {

	@TestFactory
	List<DynamicTest> semanticRuleGoldenSnapshot() throws Exception {
		List<GoldenCase> cases;
		try (var input = getClass().getResourceAsStream("/m2-rule-golden.json")) {
			cases = new Gson().fromJson(new InputStreamReader(input, UTF_8), new TypeToken<List<GoldenCase>>() {}.getType());
		}
		BuildingRuleEngine rules = new BuildingRuleEngine();
		Osm2WorldEngineAdapter engine = new Osm2WorldEngineAdapter(new OsmTagMapper(), rules);
		List<DynamicTest> tests = new ArrayList<>();
		for (GoldenCase golden : cases) {
			tests.add(DynamicTest.dynamicTest(golden.name(), () -> {
				var feature = TestBuildingFactory.rectangle(golden.name(), golden.input().longitude(),
						golden.input().latitude(), golden.input().width(), golden.input().depth(), golden.input().height());
				ModelingConfig config = ModelingConfig.defaults()
						.withRoofMode(RoofMode.valueOf(golden.input().roofMode()))
						.withStylePreset(StylePresetId.parse(golden.input().preset()));
				var style = rules.evaluate(feature, config).style();
				assertEquals(golden.expected().levels(), style.levels());
				assertEquals(golden.expected().roofShape(), style.roofShape());
				assertEquals(golden.expected().roofHeight(), style.roofHeightMeters(), 1e-9);
				assertEquals(golden.expected().outputHash(), style.outputHash(), style::toString);
				var generated = engine.generateRegion(golden.name(), List.of(feature), config.withLod(2));
				assertEquals(golden.expected().meshCount(), generated.meshCount());
				assertEquals(golden.expected().triangleCount(), generated.metrics().triangleCount());
				assertEquals(0.0, generated.metrics().minimumY(), 1e-9);
				assertEquals(golden.input().height(), generated.metrics().maximumY(), 1e-9);
			}));
		}
		return tests;
	}

	private record GoldenCase(String name, GoldenInput input, GoldenExpected expected) {}
	private record GoldenInput(double longitude, double latitude, double width, double depth, double height,
			String roofMode, String preset) {}
	private record GoldenExpected(int levels, String roofShape, double roofHeight, String outputHash,
			int meshCount, long triangleCount) {}
}
