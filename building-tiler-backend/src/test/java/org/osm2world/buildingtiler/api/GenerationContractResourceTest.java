package org.osm2world.buildingtiler.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

class GenerationContractResourceTest {

	@Test
	void manifestAndReportSchemasAreVersionedAndDescribeTheMvpDecisions() throws Exception {
		var manifest = json("/contracts/generation-manifest.schema.json");
		var report = json("/contracts/generation-report.schema.json");
		assertEquals("1.0", manifest.getAsJsonObject("properties")
				.getAsJsonObject("schemaVersion").get("const").getAsString());
		assertEquals(15, manifest.getAsJsonObject("properties")
				.getAsJsonObject("zoom").get("default").getAsInt());
		assertEquals(22, manifest.getAsJsonObject("properties")
				.getAsJsonObject("zoom").get("maximum").getAsInt());
		assertEquals(java.util.List.of(2, 3, 4), manifest.getAsJsonObject("properties").getAsJsonObject("lods")
				.getAsJsonObject("items").getAsJsonArray("enum").asList().stream()
				.map(value -> value.getAsInt()).toList());
		assertEquals("3DTILES", manifest.getAsJsonObject("properties").getAsJsonObject("outputFormats")
				.getAsJsonArray("prefixItems").get(0).getAsJsonObject().get("const").getAsString());
		assertEquals(java.util.List.of("GEOJSON", "SHP"),
				manifest.getAsJsonObject("properties").getAsJsonObject("sourceFormat")
						.getAsJsonArray("enum").asList().stream().map(value -> value.getAsString()).toList());
		assertTrue(report.getAsJsonArray("required").asList().stream()
				.anyMatch(value -> "successfulTileContents".equals(value.getAsString())));
		assertTrue(report.getAsJsonArray("required").asList().stream()
				.anyMatch(value -> "outputBytes".equals(value.getAsString())));
		assertTrue(report.getAsJsonArray("required").asList().stream()
				.anyMatch(value -> "resourceMetrics".equals(value.getAsString())));
	}

	@Test
	void openApiPublishesJobLifecycleSseCancelAndAllArtifacts() throws Exception {
		try (InputStream input = getClass().getResourceAsStream("/contracts/vector2world-m3.openapi.yaml")) {
			assertNotNull(input);
			String yaml = new String(input.readAllBytes(), UTF_8);
			assertTrue(yaml.contains("/api/jobs/{jobId}/events:"));
			assertTrue(yaml.contains("Last-Event-ID"));
			assertTrue(yaml.contains("cancelGenerationJob"));
			assertTrue(yaml.contains("/api/jobs/{jobId}/download:"));
			assertTrue(yaml.contains("/api/jobs/{jobId}/diagnostics:"));
			assertTrue(yaml.contains("/api/jobs/{jobId}/retry-failed:"));
			assertTrue(yaml.contains("COMPLETED_WITH_WARNINGS"));
			assertTrue(yaml.contains("default: [3DTILES]"));
		}
	}

	private static com.google.gson.JsonObject json(String resource) throws Exception {
		try (InputStream input = GenerationContractResourceTest.class.getResourceAsStream(resource)) {
			assertNotNull(input);
			return JsonParser.parseReader(new InputStreamReader(input, UTF_8)).getAsJsonObject();
		}
	}
}
