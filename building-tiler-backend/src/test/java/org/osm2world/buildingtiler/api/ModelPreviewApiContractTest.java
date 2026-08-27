package org.osm2world.buildingtiler.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osm2world.buildingtiler.application.DatasetService;
import org.osm2world.buildingtiler.application.ModelPreviewService;
import org.osm2world.buildingtiler.gis.ImportOptions;
import org.osm2world.buildingtiler.gis.UploadLimits;
import org.osm2world.buildingtiler.modeling.BuildingRuleEngine;
import org.osm2world.buildingtiler.modeling.OsmTagMapper;
import org.osm2world.buildingtiler.modeling.RepresentativeSampleSelector;
import org.osm2world.buildingtiler.osm2world.Osm2WorldEngineAdapter;
import org.osm2world.buildingtiler.tiles.ModelPreviewWriterAdapter;
import org.osm2world.buildingtiler.tiles.TilesetValidator;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

class ModelPreviewApiContractTest {

	@TempDir Path temporary;

	@Test
	void createStatusArtifactsAndDeleteFormCompleteApiLifecycle() throws Exception {
		DatasetService datasets = new DatasetService(temporary.resolve("datasets"), UploadLimits.defaults());
		Path input = Path.of(getClass().getResource("/m0-polygons.geojson").toURI());
		String datasetId;
		try (var stream = Files.newInputStream(input)) {
			datasetId = datasets.upload("sample.geojson", "application/geo+json", Files.size(input), stream,
					ImportOptions.defaults()).id().toString();
		}
		var engine = new Osm2WorldEngineAdapter(new OsmTagMapper(), new BuildingRuleEngine());
		var previews = new ModelPreviewService(temporary.resolve("previews"), Duration.ofHours(1), datasets,
				new RepresentativeSampleSelector(), new ModelPreviewWriterAdapter(engine, new TilesetValidator()));
		MockMvc mvc = MockMvcBuilders.standaloneSetup(new ModelPreviewController(previews))
				.setControllerAdvice(new ApiExceptionHandler()).build();
		String request = """
				{"datasetId":"%s","heightField":"Elevation","heightUnit":"m",
				 "ruleVersion":"m2-rules-v1","roofMode":"AUTO_SIMPLE",
				 "stylePreset":"warm-residential","lod":2,"sampleSize":50}
				""".formatted(datasetId);
		String response = mvc.perform(post("/api/model-previews").contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.config.ruleVersion").value("m2-rules-v1"))
				.andExpect(jsonPath("$.config.stylePreset").value("warm-residential"))
				.andExpect(jsonPath("$.modeledBuildings").value(3))
				.andExpect(jsonPath("$.ruleOutputHash").isNotEmpty())
				.andExpect(jsonPath("$.disclaimer").value(containsString("不代表真实建筑测绘结果")))
				.andReturn().getResponse().getContentAsString();
		String previewId = new ObjectMapper().readTree(response).get("id").asText();
		mvc.perform(get("/api/model-previews/{id}", previewId))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
		mvc.perform(get("/api/model-previews/{id}/files/tileset.json", previewId))
				.andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON));
		mvc.perform(get("/api/model-previews/{id}/files/tileset.glb", previewId))
				.andExpect(status().isOk()).andExpect(content().contentType("model/gltf-binary"));
		mvc.perform(delete("/api/model-previews/{id}", previewId)).andExpect(status().isNoContent());
		mvc.perform(delete("/api/model-previews/{id}", previewId)).andExpect(status().isNoContent());
	}

	@Test
	void rejectsInvalidPreviewConfigurationWithStableError() throws Exception {
		var previews = new ModelPreviewService(temporary.resolve("previews"), Duration.ofHours(1),
				new DatasetService(temporary.resolve("datasets"), UploadLimits.defaults()),
				new RepresentativeSampleSelector(), new ModelPreviewWriterAdapter(
						new Osm2WorldEngineAdapter(new OsmTagMapper()), new TilesetValidator()));
		MockMvc mvc = MockMvcBuilders.standaloneSetup(new ModelPreviewController(previews))
				.setControllerAdvice(new ApiExceptionHandler()).build();
		mvc.perform(post("/api/model-previews").contentType(MediaType.APPLICATION_JSON)
				.content("{\"datasetId\":\"x\",\"heightField\":\"Elevation\",\"sampleSize\":49}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}
}
