package org.osm2world.buildingtiler;

import java.nio.file.Path;
import java.time.Duration;

import org.osm2world.buildingtiler.application.DatasetService;
import org.osm2world.buildingtiler.application.GenerationJobCleanupScheduler;
import org.osm2world.buildingtiler.application.GenerationJobService;
import org.osm2world.buildingtiler.application.ModelPreviewService;
import org.osm2world.buildingtiler.application.PreviewGeoJsonService;
import org.osm2world.buildingtiler.gis.UploadLimits;
import org.osm2world.buildingtiler.modeling.BuildingRuleEngine;
import org.osm2world.buildingtiler.modeling.OsmTagMapper;
import org.osm2world.buildingtiler.modeling.RepresentativeSampleSelector;
import org.osm2world.buildingtiler.osm2world.Osm2WorldEngineAdapter;
import org.osm2world.buildingtiler.tiles.ModelPreviewWriterAdapter;
import org.osm2world.buildingtiler.tiles.Osm2WorldTileRenderer;
import org.osm2world.buildingtiler.tiles.TileOwnershipPlanner;
import org.osm2world.buildingtiler.tiles.TilesetTreeAssembler;
import org.osm2world.buildingtiler.tiles.TilesetValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootApplication
@EnableScheduling
public class Vector2WorldApplication {

	public static void main(String[] args) {
		SpringApplication.run(Vector2WorldApplication.class, args);
	}

	@Bean
	DatasetService datasetService(
			@Value("${vector2world.datasets.storage-root:${java.io.tmpdir}/vector2world/datasets}") String storageRoot) {
		return new DatasetService(Path.of(storageRoot), UploadLimits.defaults());
	}

	@Bean
	PreviewGeoJsonService previewGeoJsonService(ObjectMapper mapper) {
		return new PreviewGeoJsonService(mapper);
	}

	@Bean
	BuildingRuleEngine buildingRuleEngine() {
		return new BuildingRuleEngine();
	}

	@Bean
	Osm2WorldEngineAdapter osm2WorldEngineAdapter(BuildingRuleEngine rules) {
		return new Osm2WorldEngineAdapter(new OsmTagMapper(), rules);
	}

	@Bean
	ModelPreviewWriterAdapter modelPreviewWriterAdapter(Osm2WorldEngineAdapter engine) {
		return new ModelPreviewWriterAdapter(engine, new TilesetValidator());
	}

	@Bean
	ModelPreviewService modelPreviewService(
			@Value("${vector2world.previews.storage-root:${java.io.tmpdir}/vector2world/model-previews}") String storageRoot,
			@Value("${vector2world.previews.ttl-hours:2}") long ttlHours,
			DatasetService datasets, ModelPreviewWriterAdapter writer) {
		return new ModelPreviewService(Path.of(storageRoot), Duration.ofHours(ttlHours), datasets,
				new RepresentativeSampleSelector(), writer);
	}

	@Bean(destroyMethod = "close")
	GenerationJobService generationJobService(
			@Value("${vector2world.jobs.storage-root:${java.io.tmpdir}/vector2world/jobs}") String storageRoot,
			@Value("${vector2world.jobs.ttl-hours:24}") long ttlHours,
			@Value("${vector2world.jobs.max-workers:0}") int maxWorkers,
			@Value("${vector2world.jobs.queue-capacity:128}") int queueCapacity,
			DatasetService datasets, Osm2WorldEngineAdapter engine) throws java.io.IOException {
		return new GenerationJobService(Path.of(storageRoot), Duration.ofHours(ttlHours), maxWorkers,
				queueCapacity, datasets, new TileOwnershipPlanner(), new Osm2WorldTileRenderer(engine),
				new TilesetTreeAssembler(), new TilesetValidator());
	}

	@Bean
	GenerationJobCleanupScheduler generationJobCleanupScheduler(GenerationJobService jobs) {
		return new GenerationJobCleanupScheduler(jobs);
	}
}
