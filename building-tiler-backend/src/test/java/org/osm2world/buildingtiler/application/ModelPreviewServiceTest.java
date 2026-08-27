package org.osm2world.buildingtiler.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.domain.HeightUnit;
import org.osm2world.buildingtiler.domain.InvalidHeightPolicy;
import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.gis.ImportOptions;
import org.osm2world.buildingtiler.gis.UploadLimits;
import org.osm2world.buildingtiler.modeling.BuildingRuleEngine;
import org.osm2world.buildingtiler.modeling.OsmTagMapper;
import org.osm2world.buildingtiler.modeling.RepresentativeSampleSelector;
import org.osm2world.buildingtiler.osm2world.Osm2WorldEngineAdapter;
import org.osm2world.buildingtiler.tiles.ModelPreviewWriterAdapter;
import org.osm2world.buildingtiler.tiles.TilesetValidator;

class ModelPreviewServiceTest {

	@TempDir Path temporary;

	@Test
	void createsValidatedSelfContainedPreviewAndDeletesIdempotently() throws Exception {
		Fixture fixture = fixture(Duration.ofHours(1));
		ManagedModelPreview preview = fixture.previews().create(fixture.datasetId(), mapping(),
				ModelingConfig.defaults().withLod(2));
		assertEquals(ModelPreviewStatus.READY, preview.status());
		assertTrue(preview.result().validation().valid());
		assertTrue(preview.result().modeledBuildings() > 0);
		assertTrue(Files.isRegularFile(fixture.previews().resultFile(preview.id().toString(), "tileset.json")));
		assertTrue(Files.isRegularFile(fixture.previews().resultFile(preview.id().toString(), "tileset.glb")));
		assertTrue(Files.isRegularFile(fixture.previews().resultFile(preview.id().toString(), "preview-report.json")));
		assertTrue(fixture.previews().delete(preview.id().toString()));
		assertFalse(fixture.previews().delete(preview.id().toString()));
	}

	@Test
	void concurrentPreviewsAreIsolatedAndDeterministic() throws Exception {
		Fixture fixture = fixture(Duration.ofHours(1));
		var executor = Executors.newFixedThreadPool(2);
		try {
			List<Callable<ManagedModelPreview>> calls = List.of(
					() -> fixture.previews().create(fixture.datasetId(), mapping(), ModelingConfig.defaults().withLod(2)),
					() -> fixture.previews().create(fixture.datasetId(), mapping(), ModelingConfig.defaults().withLod(2)));
			var results = executor.invokeAll(calls).stream().map(future -> {
				try { return future.get(); }
				catch (Exception exception) { throw new IllegalStateException(exception); }
			}).toList();
			assertEquals(2, results.stream().map(ManagedModelPreview::id).distinct().count());
			assertEquals(1, results.stream().map(value -> value.result().ruleOutputHash()).distinct().count());
			assertTrue(results.stream().allMatch(value -> value.result().validation().valid()));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void expiredPreviewAndOrphanDirectoryAreCleaned() throws Exception {
		Fixture fixture = fixture(Duration.ofMillis(1));
		ManagedModelPreview preview = fixture.previews().create(fixture.datasetId(), mapping(),
				ModelingConfig.defaults().withLod(2));
		Path orphan = temporary.resolve("previews").resolve("preview-orphan");
		Files.createDirectory(orphan);
		Files.setLastModifiedTime(orphan, java.nio.file.attribute.FileTime.from(Instant.EPOCH));
		assertTrue(fixture.previews().cleanupExpired(Instant.now().plusSeconds(1)) >= 2);
		assertThrows(Exception.class, () -> fixture.previews().get(preview.id().toString()));
		assertFalse(Files.exists(orphan));
	}

	private Fixture fixture(Duration ttl) throws Exception {
		DatasetService datasets = new DatasetService(temporary.resolve("datasets"), UploadLimits.defaults());
		Path input = Path.of(getClass().getResource("/m0-polygons.geojson").toURI());
		ManagedDataset dataset;
		try (var stream = Files.newInputStream(input)) {
			dataset = datasets.upload("sample.geojson", "application/geo+json", Files.size(input),
					stream, ImportOptions.defaults());
		}
		var engine = new Osm2WorldEngineAdapter(new OsmTagMapper(), new BuildingRuleEngine());
		var writer = new ModelPreviewWriterAdapter(engine, new TilesetValidator());
		var previews = new ModelPreviewService(temporary.resolve("previews"), ttl, datasets,
				new RepresentativeSampleSelector(), writer);
		return new Fixture(dataset.id().toString(), previews);
	}

	private static HeightMapping mapping() {
		return new HeightMapping("Elevation", HeightUnit.M, InvalidHeightPolicy.SKIP, 10_000);
	}

	private record Fixture(String datasetId, ModelPreviewService previews) {}
}
