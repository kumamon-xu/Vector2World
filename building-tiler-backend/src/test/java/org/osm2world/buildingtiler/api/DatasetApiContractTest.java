package org.osm2world.buildingtiler.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osm2world.buildingtiler.application.DatasetService;
import org.osm2world.buildingtiler.application.PreviewGeoJsonService;
import org.osm2world.buildingtiler.gis.UploadLimits;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class DatasetApiContractTest {

	@TempDir Path temporaryDirectory;
	private MockMvc mvc;
	private ObjectMapper mapper;

	@BeforeEach
	void setUp() {
		mapper = new ObjectMapper().findAndRegisterModules();
		DatasetService datasets = new DatasetService(temporaryDirectory.resolve("datasets"), UploadLimits.defaults());
		DatasetController controller = new DatasetController(datasets, new PreviewGeoJsonService(mapper));
		mvc = MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new ApiExceptionHandler())
				.build();
	}

	@Test
	void completeDatasetHttpLifecycleMatchesVersionedContract() throws Exception {
		byte[] fixture = getClass().getResourceAsStream("/m0-polygons.geojson").readAllBytes();
		MockMultipartFile file = new MockMultipartFile("file", "buildings.geojson",
				"application/geo+json", fixture);
		String response = mvc.perform(multipart("/api/datasets").file(file)
						.param("heightField", "Elevation").param("heightUnit", "m"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.schemaVersion").value("1.0"))
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.format").value("GEOJSON"))
				.andExpect(jsonPath("$.featureCount").value(4))
				.andExpect(jsonPath("$.validGeometryCount").value(4))
				.andExpect(jsonPath("$.fields[?(@.name == 'Elevation')]").exists())
				.andExpect(jsonPath("$.heightQuality.valid").value(3))
				.andReturn().getResponse().getContentAsString();
		JsonNode json = mapper.readTree(response);
		String id = json.get("datasetId").asText();

		mvc.perform(get("/api/datasets/{id}/preview", id))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("application/geo+json"))
				.andExpect(jsonPath("$.type").value("FeatureCollection"))
				.andExpect(jsonPath("$.features[0].properties.partCount").exists())
				.andExpect(content().string(not(containsString("Elevation"))))
				.andExpect(content().string(not(containsString("upload.geojson"))));

		mvc.perform(post("/api/datasets/{id}/height-mapping", id)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fieldName\":\"Elevation\",\"unit\":\"cm\",\"invalidPolicy\":\"SKIP\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.heightQuality.minimumMeters").value(0.12))
				.andExpect(jsonPath("$.heightMapping.unit").value("CM"));

		mvc.perform(delete("/api/datasets/{id}", id)).andExpect(status().isNoContent());
		mvc.perform(delete("/api/datasets/{id}", id)).andExpect(status().isNoContent());
		mvc.perform(get("/api/datasets/{id}", id))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("DATASET_NOT_FOUND"));
	}

	@Test
	void returnsStableErrorCodeForExtensionSignatureMismatch() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "fake.geojson",
				"application/json", new byte[] { 'P', 'K', 3, 4, 0 });
		mvc.perform(multipart("/api/datasets").file(file))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.schemaVersion").value("1.0"))
				.andExpect(jsonPath("$.code").value("CONTENT_TYPE_MISMATCH"));
	}
}
