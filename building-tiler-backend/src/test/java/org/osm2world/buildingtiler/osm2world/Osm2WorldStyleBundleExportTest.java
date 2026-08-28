package org.osm2world.buildingtiler.osm2world;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.osm2world.output.common.compression.Compression.NONE;
import static org.osm2world.output.gltf.GltfFlavor.GLB;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.domain.RoofMode;
import org.osm2world.buildingtiler.domain.StylePresetId;
import org.osm2world.buildingtiler.modeling.BuildingRuleEngine;
import org.osm2world.buildingtiler.modeling.OsmTagMapper;
import org.osm2world.buildingtiler.support.TestBuildingFactory;
import org.osm2world.buildingtiler.tiles.FinalGlbFeatureIndex;
import org.osm2world.buildingtiler.tiles.TilesetValidator;
import org.osm2world.output.tileset.TilesetOutput;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class Osm2WorldStyleBundleExportTest {

	@TempDir Path temporaryDirectory;

	@Test
	void lod2FinalGlbEmbedsRealTexturesAndTextureCoordinatesForEveryPreset() throws Exception {
		Set<String> selectedWallMaterials = new HashSet<>();
		for (StylePresetId preset : StylePresetId.values()) {
			ModelingConfig config = ModelingConfig.defaults().withLod(2).withStylePreset(preset);
			var engine = new Osm2WorldEngineAdapter(new OsmTagMapper(), new BuildingRuleEngine());
			String sourceId = "建筑-" + preset.value();
			var generated = engine.generateRegion(preset.value(), List.of(TestBuildingFactory.rectangle(
					sourceId, 116.6, 39.9, 0.00025, 0.0002, 18)), config);
			selectedWallMaterials.add(generated.styles().get(0).style().wallMaterial());

			Path directory = temporaryDirectory.resolve(preset.value());
			Files.createDirectory(directory);
			Path tileset = directory.resolve("tileset.json");
			TilesetOutput output = new TilesetOutput(tileset.toFile(), GLB, NONE,
					generated.projection(), generated.boundary());
			output.setConfiguration(generated.configuration());
			output.outputScene(generated.scene());
			copyForOfficialPatchGate(directory.resolve("tileset.glb"), preset);
			assertTrue(new FinalGlbFeatureIndex().readPartIds(directory.resolve("tileset.glb"))
					.contains(sourceId + "/part/0"), "Non-ASCII feature metadata must remain UTF-8");

			JsonObject gltf = readGlbJson(directory.resolve("tileset.glb"));
			assertTrue(gltf.getAsJsonArray("images").size() > 0, preset + " must embed images");
			assertTrue(gltf.getAsJsonArray("textures").size() > 0, preset + " must embed textures");
			assertTrue(gltf.getAsJsonArray("materials").asList().stream().anyMatch(material -> {
				JsonObject pbr = material.getAsJsonObject().getAsJsonObject("pbrMetallicRoughness");
				return pbr != null && pbr.has("baseColorTexture");
			}), preset + " must bind a base color texture");
			assertTrue(gltf.getAsJsonArray("meshes").asList().stream()
					.flatMap(mesh -> mesh.getAsJsonObject().getAsJsonArray("primitives").asList().stream())
					.anyMatch(primitive -> primitive.getAsJsonObject().getAsJsonObject("attributes")
							.has("TEXCOORD_0")), preset + " must export texture coordinates");
		}
		assertTrue(selectedWallMaterials.size() >= 3, "Presets must select materially different facades");
	}

	private static void copyForOfficialPatchGate(Path glb, StylePresetId preset) throws Exception {
		String output = System.getProperty("vector2world.patchGateOutput");
		if (output == null || output.isBlank()) return;
		Path directory = Path.of(output).toAbsolutePath().normalize();
		Files.createDirectories(directory);
		Files.copy(glb, directory.resolve(preset.value() + ".glb"),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	@Test
	void styleBundleIsVersionedHashedAndForcesTheExpectedWindowProfile() {
		var factory = new Osm2WorldConfigFactory();
		var info = factory.bundleInfo();
		assertEquals(Osm2WorldConfigFactory.BUNDLE_VERSION, info.version());
		assertTrue(info.sha256().matches("[0-9a-f]{64}"));
		assertTrue(Files.isRegularFile(Path.of(info.materializedDirectory()).resolve("LICENSE.txt")));
		assertEquals("FLAT_TEXTURES", factory.create(ModelingConfig.defaults().withLod(2), false)
				.getString("explicitWindowImplementation"));
		assertEquals("FULL_GEOMETRY", factory.create(ModelingConfig.defaults().withLod(3), false)
				.getString("explicitWindowImplementation"));
	}

	@Test
	void finalGlbProvesPitchedRoofGeometryExactHeightAndQualityComplexity() throws Exception {
		var building = TestBuildingFactory.rectangle("roof-profile", 116.6, 39.9,
				0.00025, 0.0002, 18, java.util.Map.of("roof:shape", "gabled"));
		var flat = exportAndValidate("flat", building,
				ModelingConfig.defaults().withLod(2).withRoofMode(RoofMode.CONSERVATIVE));
		var pitched = exportAndValidate("pitched", building,
				ModelingConfig.defaults().withLod(2).withRoofMode(RoofMode.AUTO_SIMPLE));
		var detailed = exportAndValidate("detailed", building,
				ModelingConfig.defaults().withLod(4).withRoofMode(RoofMode.AUTO_SIMPLE));

		assertEquals(0, flat.slopedSurfaceTriangleCount(), "forced-flat mode must not contain sloped roof faces");
		assertTrue(pitched.slopedSurfaceTriangleCount() > 0,
				"AUTO_SIMPLE gabled roof must survive into final GLB vertices");
		assertEquals(18, flat.maximumModelHeight(), 0.02);
		assertEquals(18, pitched.maximumModelHeight(), 0.02);
		assertTrue(detailed.triangleCount() > pitched.triangleCount() * 1.5,
				"LOD4 facade/window geometry must be materially more detailed than LOD2");
	}

	private TilesetValidator.ValidationResult exportAndValidate(String name,
			org.osm2world.buildingtiler.domain.BuildingFeature building, ModelingConfig config) throws Exception {
		var engine = new Osm2WorldEngineAdapter(new OsmTagMapper(), new BuildingRuleEngine());
		var generated = engine.generateRegion(name, List.of(building), config);
		Path directory = Files.createDirectory(temporaryDirectory.resolve(name));
		TilesetOutput output = new TilesetOutput(directory.resolve("tileset.json").toFile(), GLB, NONE,
				generated.projection(), generated.boundary());
		output.setConfiguration(generated.configuration());
		output.outputScene(generated.scene());
		var validation = new TilesetValidator().validate(directory, 1);
		assertTrue(validation.valid(), validation.errors().toString());
		return validation;
	}

	private static JsonObject readGlbJson(Path file) throws Exception {
		byte[] bytes = Files.readAllBytes(file);
		ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(0x46546c67, buffer.getInt());
		assertEquals(2, buffer.getInt());
		assertEquals(bytes.length, buffer.getInt());
		int jsonLength = buffer.getInt();
		assertEquals(0x4e4f534a, buffer.getInt());
		byte[] json = new byte[jsonLength];
		buffer.get(json);
		JsonObject result = JsonParser.parseString(new String(json, UTF_8).trim()).getAsJsonObject();
		assertNotNull(result.getAsJsonObject("asset"));
		return result;
	}
}
