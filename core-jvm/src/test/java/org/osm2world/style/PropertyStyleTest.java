package org.osm2world.style;

import static java.util.Objects.requireNonNull;
import static org.junit.Assert.*;
import static org.osm2world.scene.material.DefaultMaterials.*;
import static org.osm2world.util.test.TestFileUtil.getTestFile;

import java.io.File;
import java.util.Map;

import org.junit.Test;
import org.osm2world.conversion.O2WConfig;
import org.osm2world.scene.color.Color;
import org.osm2world.scene.material.*;

public class PropertyStyleTest {

	@Test
	public void testResolveMaterial() {

		var style = new PropertyStyle(new O2WConfig(Map.of(
				"material_ASPHALT_color", "#AAAAAA",
				"material_COPPER_ROOF_color", "#FF0000"
		)));

		assertEquals(Color.decode("#AAAAAA"),
				requireNonNull(style.resolveMaterial("asphalt")).color());
		assertEquals(Color.decode("#AAAAAA"),
				requireNonNull(style.resolveMaterial("Asphalt")).color());
		assertEquals(Color.decode("#FF0000"),
				requireNonNull(style.resolveMaterial("COPPER_ROOF")).color());

		assertEquals(WATER.defaultAppearance(), style.resolveMaterial("water"));
		assertEquals(WATER.defaultAppearance(), style.resolveMaterial("WATER"));

	}

	@Test
	public void testTextureAttributes() {

		var style = new PropertyStyle(new O2WConfig(Map.of(
				"material_ROAD_MARKING_ARROW_THROUGH_texture0_file", "./textures/road_arrow_through.png",
				"material_ROAD_MARKING_ARROW_THROUGH_texture0_width", "5",
				"material_ROAD_MARKING_ARROW_THROUGH_texture0_wrap", "CLAMP_TO_BORDER",
				"material_ROAD_MARKING_ARROW_RIGHT_texture0_color_file", "./textures/road_arrow_right.png",
				"material_ROAD_MARKING_ARROW_RIGHT_texture0_height", "1.2",
				"material_ROAD_MARKING_ARROW_RIGHT_texture0_wrap", "CLAMP_TO_BORDER"
		)));

		TextureLayer layerA = style.resolveMaterial(ROAD_MARKING_ARROW_THROUGH).textureLayers().get(0);
		assertTrue(layerA.baseColorTexture instanceof UriTexture t && t.getUri().getPath().contains("through"));
		assertEquals(5, layerA.baseColorTexture.dimensions.width(), 0);
		assertEquals(TextureData.Wrap.CLAMP, layerA.baseColorTexture.wrap);

		TextureLayer layerB = style.resolveMaterial(ROAD_MARKING_ARROW_RIGHT).textureLayers().get(0);
		assertTrue(layerB.baseColorTexture instanceof UriTexture t && t.getUri().getPath().contains("right"));
		assertEquals(1.2, layerB.baseColorTexture.dimensions.height(), 0);
		assertEquals(TextureData.Wrap.CLAMP, layerB.baseColorTexture.wrap);

	}

	@Test
	public void testGetTransparentVariant() {

		var style = new PropertyStyle(new O2WConfig(Map.of(
				"configBaseURI", "file:" + getTestFile("config").getAbsolutePath() + File.separator,
				"material_GLASS_TRANSPARENT_texture0_dir", "./textures/Glass",
				"material_GLASS_TRANSPARENT_texture0_color_file", "./textures/Glass/Glass_Transparent_Color.png"
		)));

		Material transparentVariant = style.getTransparentVariant(GLASS);
		assertNotNull(transparentVariant);

		TextureData colorTexture = transparentVariant.textureLayers().get(0).baseColorTexture;
		if (colorTexture instanceof ImageFileTexture imageFileTexture) {
			assertEquals("Glass_Transparent_Color.png", imageFileTexture.getFile().getName());
		} else {
			fail("Expected ImageFileTexture");
		}

	}

}