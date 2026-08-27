package org.osm2world.buildingtiler.modeling;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.osm2world.buildingtiler.domain.BuildingFeature;
import org.osm2world.buildingtiler.domain.BuildingStyle;
import org.osm2world.buildingtiler.domain.FootprintMetrics;
import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.domain.RoofMode;
import org.osm2world.buildingtiler.domain.StyledBuilding;

public final class BuildingRuleEngine {

	private static final Set<String> MATERIALS = Set.of(
			"adobe", "brick", "concrete", "glass", "metal", "steel", "stone", "tiles", "wood");
	private final StylePresetCatalog presets;
	private final FootprintAnalyzer footprints;
	private final StableStyleHash hashes;
	private final BuildingDimensionRules dimensions;

	public BuildingRuleEngine() {
		this(new StylePresetCatalog(), new FootprintAnalyzer(), new StableStyleHash(), new BuildingDimensionRules());
	}

	public BuildingRuleEngine(StylePresetCatalog presets, FootprintAnalyzer footprints,
			StableStyleHash hashes, BuildingDimensionRules dimensions) {
		this.presets = presets;
		this.footprints = footprints;
		this.hashes = hashes;
		this.dimensions = dimensions;
	}

	public StyledBuilding evaluate(BuildingFeature feature, ModelingConfig config) {
		if (feature == null || config == null) throw new IllegalArgumentException("Feature and config are required");
		FootprintMetrics metrics = footprints.analyze(feature.geometryWgs84(), config.footprintThresholds());
		int variant = hashes.variantBucket(feature, config, 256);
		var dimension = dimensions.evaluate(feature, metrics, config, variant);
		StylePreset preset = presets.get(config.stylePreset());
		List<String> reasons = new ArrayList<>(dimension.reasons());
		Map<String, String> provenance = new LinkedHashMap<>();
		provenance.put("height", "SOURCE:M1_HEIGHT_MAPPING");
		provenance.put("building:levels", "RULE:" + config.ruleVersion().value());
		provenance.put("roof:shape", dimension.reasons().stream().anyMatch(value -> value.startsWith("roof shape:")
				&& value.contains("source attribute"))
				? "SOURCE" : "RULE:" + config.roofMode().name());
		provenance.put("roof:height", dimension.roofHeightMeters() > 0 ? "RULE_OR_SOURCE" : "RULE:FLAT");

		String wallMaterial = chooseMaterial(feature, preset.wallMaterials(), variant,
				"building:material", "building_material");
		String roofMaterial = chooseMaterial(feature, preset.roofMaterials(), variant / 3,
				"roof:material", "roof_material");
		String wallColor = chooseColor(feature, preset.wallColors(), variant / 5,
				"building:colour", "building:color", "building_colour", "building_color");
		String roofColor = chooseColor(feature, preset.roofColors(), variant / 7,
				"roof:colour", "roof:color", "roof_colour", "roof_color");
		boolean windows = preset.windows() && !"glass".equals(wallMaterial) && dimension.levels() > 0;
		if (config.roofMode() == RoofMode.FLAT_FACADE_DETAIL && !"glass".equals(wallMaterial)) windows = true;
		String explicitWindow = BuildingDimensionRules.text(BuildingDimensionRules.attribute(feature, "window", "windows"));
		if (explicitWindow != null) windows = !Set.of("no", "false", "0").contains(explicitWindow.toLowerCase(Locale.ROOT));

		provenance.put("building:material", isSourceMaterial(feature, "building:material", "building_material")
				? "SOURCE" : "PRESET:" + preset.id().value() + "@" + preset.version());
		provenance.put("roof:material", isSourceMaterial(feature, "roof:material", "roof_material")
				? "SOURCE" : "PRESET:" + preset.id().value() + "@" + preset.version());
		provenance.put("building:colour", isSourceColor(feature, "building:colour", "building:color", "building_colour", "building_color")
				? "SOURCE" : "PRESET:" + preset.id().value() + "@" + preset.version());
		provenance.put("roof:colour", isSourceColor(feature, "roof:colour", "roof:color", "roof_colour", "roof_color")
				? "SOURCE" : "PRESET:" + preset.id().value() + "@" + preset.version());
		provenance.put("window", explicitWindow == null ? "PRESET_OR_MODE" : "SOURCE");
		reasons.add("materials/colors: " + preset.id().value() + " stable variant " + variant);

		String canonicalStyle = String.join("|", Integer.toString(dimension.levels()), dimension.roofShape(),
				StableStyleHash.decimal(dimension.roofHeightMeters()), wallMaterial, roofMaterial,
				wallColor, roofColor, Boolean.toString(windows), Integer.toString(variant));
		String outputHash = hashes.outputHash(feature, config, canonicalStyle);
		BuildingStyle style = new BuildingStyle(feature.heightMeters(), dimension.levels(), dimension.roofShape(),
				dimension.roofHeightMeters(), wallMaterial, roofMaterial, wallColor, roofColor, windows,
				variant, preset.id(), config.ruleVersion(), outputHash, reasons, provenance);
		return new StyledBuilding(feature, metrics, style);
	}

	private static String chooseMaterial(BuildingFeature feature, List<String> variants, int bucket, String... aliases) {
		String source = BuildingDimensionRules.text(BuildingDimensionRules.attribute(feature, aliases));
		if (source != null) {
			source = source.toLowerCase(Locale.ROOT);
			if (MATERIALS.contains(source)) return source;
		}
		return variants.get(Math.floorMod(bucket, variants.size()));
	}

	private static String chooseColor(BuildingFeature feature, List<String> variants, int bucket, String... aliases) {
		String source = BuildingDimensionRules.text(BuildingDimensionRules.attribute(feature, aliases));
		if (source != null && source.matches("#[0-9a-fA-F]{6}")) return source.toLowerCase(Locale.ROOT);
		return variants.get(Math.floorMod(bucket, variants.size())).toLowerCase(Locale.ROOT);
	}

	private static boolean isSourceMaterial(BuildingFeature feature, String... aliases) {
		String source = BuildingDimensionRules.text(BuildingDimensionRules.attribute(feature, aliases));
		return source != null && MATERIALS.contains(source.toLowerCase(Locale.ROOT));
	}

	private static boolean isSourceColor(BuildingFeature feature, String... aliases) {
		String source = BuildingDimensionRules.text(BuildingDimensionRules.attribute(feature, aliases));
		return source != null && source.matches("#[0-9a-fA-F]{6}");
	}
}
