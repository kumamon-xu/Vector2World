package org.osm2world.buildingtiler;

import java.nio.file.Path;

import org.osm2world.buildingtiler.application.DatasetService;
import org.osm2world.buildingtiler.application.PreviewGeoJsonService;
import org.osm2world.buildingtiler.gis.UploadLimits;
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
}
