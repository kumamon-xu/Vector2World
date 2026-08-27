package org.osm2world.buildingtiler.modeling;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.osm2world.buildingtiler.domain.StylePresetId;

public final class StylePresetCatalog {

	public static final String PRESET_VERSION = "m2-presets-v1";
	private static final Set<String> SUPPORTED_OSM2WORLD_MATERIALS = Set.of(
			"adobe", "brick", "concrete", "glass", "metal", "steel", "stone", "tiles", "wood");
	private final Map<StylePresetId, StylePreset> presets;

	public StylePresetCatalog() {
		EnumMap<StylePresetId, StylePreset> loaded = new EnumMap<>(StylePresetId.class);
		for (StylePresetId id : StylePresetId.values()) loaded.put(id, load(id));
		presets = Map.copyOf(loaded);
	}

	public StylePreset get(StylePresetId id) {
		StylePreset preset = presets.get(id);
		if (preset == null) throw new IllegalArgumentException("Style preset is unavailable: " + id);
		return preset;
	}

	public Map<StylePresetId, StylePreset> all() {
		return presets;
	}

	private static StylePreset load(StylePresetId id) {
		String resource = "/styles/presets/" + id.value() + ".properties";
		Properties properties = new Properties();
		try (InputStream input = StylePresetCatalog.class.getResourceAsStream(resource)) {
			if (input == null) throw new IllegalStateException("Missing required style preset resource: " + resource);
			properties.load(input);
		} catch (IOException exception) {
			throw new IllegalStateException("Cannot read style preset resource: " + resource, exception);
		}
		String configuredId = required(properties, "id");
		if (!id.value().equals(configuredId)) throw new IllegalStateException("Preset id mismatch in " + resource);
		List<String> walls = list(properties, "wall.materials");
		List<String> roofs = list(properties, "roof.materials");
		for (String material : walls) validateMaterial(material, resource);
		for (String material : roofs) validateMaterial(material, resource);
		List<String> wallColors = list(properties, "wall.colors");
		List<String> roofColors = list(properties, "roof.colors");
		wallColors.forEach(color -> validateColor(color, resource));
		roofColors.forEach(color -> validateColor(color, resource));
		return new StylePreset(id, required(properties, "version"), walls, roofs,
				wallColors, roofColors, Boolean.parseBoolean(required(properties, "windows")));
	}

	private static String required(Properties properties, String key) {
		String value = properties.getProperty(key);
		if (value == null || value.isBlank()) throw new IllegalStateException("Missing preset property: " + key);
		return value.trim();
	}

	private static List<String> list(Properties properties, String key) {
		return List.of(required(properties, key).split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
	}

	private static void validateMaterial(String value, String resource) {
		if (!SUPPORTED_OSM2WORLD_MATERIALS.contains(value)) {
			throw new IllegalStateException("Unsupported OSM2World material " + value + " in " + resource);
		}
	}

	private static void validateColor(String value, String resource) {
		if (!value.matches("#[0-9a-fA-F]{6}")) {
			throw new IllegalStateException("Invalid controlled color " + value + " in " + resource);
		}
	}
}
