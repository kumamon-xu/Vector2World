package org.osm2world.buildingtiler.api;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import org.osm2world.buildingtiler.application.GenerationJobService;
import org.osm2world.buildingtiler.application.GenerationJobState;
import org.osm2world.buildingtiler.gis.ImportOptions;
import org.osm2world.buildingtiler.gis.UploadLimits;
import org.osm2world.buildingtiler.modeling.BuildingRuleEngine;
import org.osm2world.buildingtiler.modeling.OsmTagMapper;
import org.osm2world.buildingtiler.osm2world.Osm2WorldEngineAdapter;
import org.osm2world.buildingtiler.tiles.Osm2WorldTileRenderer;
import org.osm2world.buildingtiler.tiles.TileOwnershipPlanner;
import org.osm2world.buildingtiler.tiles.TilesetTreeAssembler;
import org.osm2world.buildingtiler.tiles.TilesetValidator;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

class GenerationJobApiContractTest {

	@TempDir Path temporary;

	@Test
	void createStatusArtifactsCancelAndValidationFormThePublicLifecycle() throws Exception {
		DatasetService datasets = new DatasetService(temporary.resolve("datasets"), UploadLimits.defaults());
		Path input = Path.of(getClass().getResource("/m0-polygons.geojson").toURI());
		String datasetId;
		try (var stream = Files.newInputStream(input)) {
			datasetId = datasets.upload("sample.geojson", "application/geo+json", Files.size(input), stream,
					ImportOptions.defaults()).id().toString();
		}
		var renderer = new Osm2WorldTileRenderer(
				new Osm2WorldEngineAdapter(new OsmTagMapper(), new BuildingRuleEngine()));
		try (var jobs = new GenerationJobService(temporary.resolve("jobs"), Duration.ofHours(1), 2, 4,
				datasets, new TileOwnershipPlanner(), renderer, new TilesetTreeAssembler(), new TilesetValidator())) {
			MockMvc mvc = MockMvcBuilders.standaloneSetup(new GenerationJobController(jobs))
					.setControllerAdvice(new ApiExceptionHandler()).build();
			String request = """
					{"datasetId":"%s","heightField":"Elevation","heightUnit":"m",
					 "zoom":15,"lods":[2],"workerCount":2,"queueCapacity":4,
					 "outputFormats":["3DTILES"]}
					""".formatted(datasetId);
			String response = mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(request))
					.andExpect(status().isAccepted())
					.andExpect(jsonPath("$.schemaVersion").value("1.0"))
					.andExpect(jsonPath("$.datasetId").value(datasetId))
					.andExpect(jsonPath("$.tilingConfig.zoom").value(15))
					.andExpect(jsonPath("$.tilingConfig.lods[0]").value(2))
					.andExpect(jsonPath("$.links.events").value(containsString("/events")))
					.andExpect(jsonPath("$.links.tileset").value(containsString("/files/tileset.json")))
					.andReturn().getResponse().getContentAsString();
			String jobId = new ObjectMapper().readTree(response).get("id").asText();
			var job = jobs.get(jobId);
			waitFor(() -> job.state().terminal(), 30_000);
			assertNotEquals(GenerationJobState.FAILED, job.state(), job.error());

			mvc.perform(get("/api/jobs/{id}", jobId)).andExpect(status().isOk())
					.andExpect(jsonPath("$.state", anyOf(is("COMPLETED"), is("COMPLETED_WITH_WARNINGS"))))
					.andExpect(jsonPath("$.successfulTiles").isNumber())
					.andExpect(jsonPath("$.outputBytes").isNumber());
			mvc.perform(get("/api/jobs/{id}/tileset", jobId)).andExpect(status().isOk())
					.andExpect(content().contentType(MediaType.APPLICATION_JSON));
			mvc.perform(get("/api/jobs/{id}/manifest", jobId)).andExpect(status().isOk())
					.andExpect(jsonPath("$.crossTileStrategy").value("centroid-owner/full-footprint/no-clip"));
			String manifest = mvc.perform(get("/api/jobs/{id}/manifest", jobId)).andReturn()
					.getResponse().getContentAsString();
			String tileJsonPath = new ObjectMapper().readTree(manifest).get("tileContents").get(0)
					.get("tilesetPath").asText();
			String glbPath = new ObjectMapper().readTree(manifest).get("tileContents").get(0)
					.get("glbPath").asText();
			mvc.perform(get("/api/jobs/{id}/files/{path}", jobId, tileJsonPath))
					.andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON));
			mvc.perform(get("/api/jobs/{id}/files/{path}", jobId, glbPath))
					.andExpect(status().isOk()).andExpect(content().contentType("model/gltf-binary"));
			mvc.perform(get("/api/jobs/{id}/report", jobId)).andExpect(status().isOk())
					.andExpect(jsonPath("$.validation.valid").value(true));
			mvc.perform(delete("/api/jobs/{id}", jobId)).andExpect(status().isAccepted())
					.andExpect(jsonPath("$.state", anyOf(is("COMPLETED"), is("COMPLETED_WITH_WARNINGS"))));

			mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON)
					.content(request.replace("3DTILES", "OBJ")))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
			mvc.perform(get("/api/jobs/not-a-uuid")).andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("GENERATION_JOB_NOT_FOUND"));
		}
	}

	private static void waitFor(java.util.function.BooleanSupplier condition, long timeoutMillis) throws Exception {
		long deadline = System.nanoTime() + Duration.ofMillis(timeoutMillis).toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for generation job");
			Thread.sleep(10);
		}
	}
}
