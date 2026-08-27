package org.osm2world.buildingtiler.modeling;

import java.util.ArrayList;
import java.util.List;

import org.osm2world.buildingtiler.domain.BuildingFeature;
import org.osm2world.buildingtiler.domain.FootprintMetrics;
import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.domain.RoofMode;

public final class BuildingDimensionRules {

	private static final List<String> VERIFIED_ROOFS = List.of("flat", "gabled", "hipped");

	public Dimensions evaluate(BuildingFeature feature, FootprintMetrics footprint,
			ModelingConfig config, int variantBucket) {
		List<String> reasons = new ArrayList<>();
		String roofShape = chooseRoof(feature, footprint, config, variantBucket, reasons);
		double roofHeight = 0;
		if (!"flat".equals(roofShape)) {
			Double explicit = positiveNumber(attribute(feature, "roof:height", "roof_height"));
			double desired = explicit != null ? explicit
					: Math.max(config.minimumRoofHeightMeters(), feature.heightMeters() * config.roofHeightRatio());
			roofHeight = Math.min(config.maximumRoofHeightMeters(), desired);
			roofHeight = Math.min(roofHeight, feature.heightMeters() - config.minimumBodyHeightMeters());
			if (!(roofHeight > 0)) {
				roofShape = "flat";
				roofHeight = 0;
				reasons.add("flat: total height cannot preserve the configured minimum body height");
			} else if (explicit != null) {
				reasons.add("roof height: valid source attribute");
			} else {
				reasons.add("roof height: bounded configured ratio");
			}
		}
		double bodyHeight = feature.heightMeters() - roofHeight;
		int levels = Math.max(1, (int)Math.round(bodyHeight / config.floorHeightMeters()));
		reasons.add("levels: round((height-roofHeight)/floorHeight), minimum 1");
		return new Dimensions(levels, roofShape, roofHeight, List.copyOf(reasons));
	}

	private static String chooseRoof(BuildingFeature feature, FootprintMetrics footprint,
			ModelingConfig config, int variantBucket, List<String> reasons) {
		if (config.roofMode() == RoofMode.CONSERVATIVE) {
			reasons.add("flat: conservative mode");
			return "flat";
		}
		if (config.roofMode() == RoofMode.FLAT_FACADE_DETAIL) {
			reasons.add("flat: flat facade detail mode");
			return "flat";
		}
		String explicit = text(attribute(feature, "roof:shape", "roof_shape"));
		if (explicit != null) {
			explicit = explicit.toLowerCase(java.util.Locale.ROOT);
			if (VERIFIED_ROOFS.contains(explicit)) {
				reasons.add("roof shape: verified source attribute");
				return explicit;
			}
			reasons.add("source roof shape unsupported; applying AUTO_SIMPLE fallback");
		}
		if (feature.heightMeters() < config.minimumPitchedBuildingHeightMeters()) {
			reasons.add("flat: building is below pitched-roof height range");
			return "flat";
		}
		if (feature.heightMeters() > config.maximumPitchedBuildingHeightMeters()) {
			reasons.add("flat: building is above pitched-roof height range");
			return "flat";
		}
		if (footprint.irregular()) {
			reasons.add("flat: footprint classified as irregular");
			return "flat";
		}
		String selected = variantBucket % 2 == 0 ? "gabled" : "hipped";
		reasons.add(selected + ": simple footprint and deterministic variant bucket");
		return selected;
	}

	static Object attribute(BuildingFeature feature, String... aliases) {
		for (var entry : feature.sourceAttributes().entrySet()) {
			for (String alias : aliases) {
				if (entry.getKey().equalsIgnoreCase(alias)) return entry.getValue();
			}
		}
		return null;
	}

	static String text(Object value) {
		if (value == null) return null;
		String text = value.toString().trim();
		return text.isEmpty() ? null : text;
	}

	static Double positiveNumber(Object value) {
		if (value == null) return null;
		try {
			double number = value instanceof Number numeric ? numeric.doubleValue()
					: Double.parseDouble(value.toString().trim());
			return Double.isFinite(number) && number > 0 ? number : null;
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	public record Dimensions(int levels, String roofShape, double roofHeightMeters, List<String> reasons) {}
}
