package org.osm2world.buildingtiler.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class DomainContractTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void heightMappingRoundTripsWithVersionedContract() throws Exception {
		HeightMapping source = new HeightMapping("Elevation", HeightUnit.FT,
				InvalidHeightPolicy.SKIP, 5000);
		HeightMapping restored = mapper.readValue(mapper.writeValueAsBytes(source), HeightMapping.class);
		assertEquals(source, restored);

		JsonNode schema = readJson("/contracts/dataset-metadata.schema.json");
		assertEquals("1.0", schema.at("/properties/schemaVersion/const").asText());
		assertTrue(schema.at("/required").toString().contains("heightCandidates"));
	}

	@Test
	void openApiPublishesUploadMetadataPreviewMappingAndDelete() throws Exception {
		String openApi;
		try (InputStream input = getClass().getResourceAsStream("/contracts/vector2world-m1.openapi.yaml")) {
			openApi = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
		assertTrue(openApi.contains("openapi: 3.1.0"));
		assertTrue(openApi.contains("/api/datasets:"));
		assertTrue(openApi.contains("/{datasetId}/preview:"));
		assertTrue(openApi.contains("/{datasetId}/height-mapping:"));
	}

	@Test
	void rejectsInvalidDomainValuesAtConstruction() {
		assertThrows(IllegalArgumentException.class, () -> new BuildingPartId("", 0));
		assertThrows(IllegalArgumentException.class, () -> new BuildingPartId("feature", -1));
		assertThrows(IllegalArgumentException.class,
				() -> new HeightMapping("", HeightUnit.M, InvalidHeightPolicy.SKIP, 1));
		assertThrows(IllegalArgumentException.class,
				() -> new HeightMapping("h", HeightUnit.M, InvalidHeightPolicy.SKIP, Double.NaN));
		assertThrows(IllegalArgumentException.class,
				() -> new FieldMetadata("", "Number", 0, 0, 0, null));
	}

	private JsonNode readJson(String resource) throws Exception {
		try (InputStream input = getClass().getResourceAsStream(resource)) {
			return mapper.readTree(input);
		}
	}
}
