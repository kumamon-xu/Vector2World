package org.osm2world.buildingtiler.tiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.osm2world.output.common.compression.Compression.NONE;
import static org.osm2world.output.gltf.GltfFlavor.GLB;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.modeling.BuildingRuleEngine;
import org.osm2world.buildingtiler.modeling.OsmTagMapper;
import org.osm2world.buildingtiler.osm2world.ModelingLedgerEntry.Status;
import org.osm2world.buildingtiler.osm2world.Osm2WorldEngineAdapter;
import org.osm2world.buildingtiler.support.TestBuildingFactory;
import org.osm2world.math.geo.LatLon;
import org.osm2world.math.geo.MetricMapProjection;
import org.osm2world.math.geo.TileNumber;
import org.osm2world.output.tileset.TilesetOutput;
import org.osm2world.output.tileset.TilesetTreeUtil;
import org.osm2world.scene.Scene;
import org.osm2world.scene.mesh.LevelOfDetail;

class Osm2WorldTileRendererIsolationTest {

	@TempDir Path temporary;

	@Test
	void isolatesOneFeatureThatFailsDuringFinalGltfExport() throws Exception {
		AtomicInteger exports = new AtomicInteger();
		var engine = new Osm2WorldEngineAdapter(new OsmTagMapper(), new BuildingRuleEngine());
		var renderer = new Osm2WorldTileRenderer(engine, (file, projection, bounds) ->
				new TilesetOutput(file.toFile(), GLB, NONE, projection, bounds) {
					@Override public void outputScene(Scene scene) {
						int invocation = exports.incrementAndGet();
						if (invocation == 1 || invocation == 3) {
							throw new IllegalStateException("injected glTF export failure");
						}
						super.outputScene(scene);
					}
				});
		TileNumber tile = TileNumber.atLatLon(15, new LatLon(39.9, 116.6));
		var first = TestBuildingFactory.rectangle("first", 116.6000, 39.9000, .0002, .0002, 18);
		var poisoned = TestBuildingFactory.rectangle("poisoned", 116.6004, 39.9000, .0002, .0002, 18);
		var third = TestBuildingFactory.rectangle("third", 116.6008, 39.9000, .0002, .0002, 18);

		TileRenderResult result = renderer.render(new TileWork(tile, List.of(first, poisoned, third)),
				List.of(2), ModelingConfig.defaults().withLod(2), temporary, () -> false);

		assertEquals(2, result.modeledBuildings());
		assertTrue(result.modelingLedger().stream().anyMatch(entry ->
				entry.sourceFeatureId().equals("poisoned") && entry.status() == Status.FAILED_GLTF_EXPORT));
		assertEquals(2, result.modelingLedger().stream().filter(entry -> entry.status() == Status.MODELED).count());
		Path glb = TilesetTreeUtil.tilePath(temporary, tile, LevelOfDetail.LOD2, ".glb");
		assertTrue(Files.isRegularFile(glb));
		assertEquals(java.util.Set.of("first/part/0", "third/part/0"),
				new FinalGlbFeatureIndex().readPartIds(glb));
		var projection = new MetricMapProjection(tile.latLonBounds().getCenter());
		var solidCenter = projection.toXZ(39.9001, 116.6001);
		var filled = assertThrows(java.io.IOException.class,
				() -> new FinalGlbFeatureIndex().verifyOpenHoles(glb, java.util.Map.of(
						"first/part/0", List.of(new FinalGlbFeatureIndex.HorizontalPoint(
								solidCenter.x, -solidCenter.z)))));
		assertTrue(filled.getMessage().contains("FINAL_GLTF_HOLE_FILLED"));
	}
}
