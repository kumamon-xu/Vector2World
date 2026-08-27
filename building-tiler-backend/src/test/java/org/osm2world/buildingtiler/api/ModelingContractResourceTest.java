package org.osm2world.buildingtiler.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

class ModelingContractResourceTest {

	@Test
	void modelingSchemaIsVersionedAndDeclaresAllModesAndPresets() throws Exception {
		try (InputStream input = getClass().getResourceAsStream("/contracts/modeling-config.schema.json")) {
			assertNotNull(input);
			var root = JsonParser.parseReader(new java.io.InputStreamReader(input, UTF_8)).getAsJsonObject();
			assertEquals("m2-rules-v1", root.getAsJsonObject("properties")
					.getAsJsonObject("ruleVersion").get("const").getAsString());
			assertEquals(3, root.getAsJsonObject("properties").getAsJsonObject("roofMode")
					.getAsJsonArray("enum").size());
			assertEquals(4, root.getAsJsonObject("properties").getAsJsonObject("stylePreset")
					.getAsJsonArray("enum").size());
		}
	}

	@Test
	void openApiPublishesPreviewLifecycleAndArtifacts() throws Exception {
		try (InputStream input = getClass().getResourceAsStream("/contracts/vector2world-m2.openapi.yaml")) {
			assertNotNull(input);
			String yaml = new String(input.readAllBytes(), UTF_8);
			assertTrue(yaml.contains("/api/model-previews:"));
			assertTrue(yaml.contains("/api/model-previews/{previewId}:"));
			assertTrue(yaml.contains("tileset.glb"));
			assertTrue(yaml.contains("m2-rules-v1"));
		}
	}
}
