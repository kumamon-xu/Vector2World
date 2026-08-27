package org.osm2world.buildingtiler.modeling;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.osm2world.buildingtiler.domain.BuildingFeature;
import org.osm2world.map_data.data.TagSet;

public final class OsmTagMapper {

	public static final String RULE_VERSION = "m0-rules-v1";
	private static final double FLOOR_HEIGHT_METERS = 3.2;

	public TagSet toTags(BuildingFeature feature) {
		double height = feature.heightMeters();
		int variant = Math.floorMod((RULE_VERSION + ":" + feature.id()).hashCode(), 4);
		String roofShape = height >= 30 ? "flat" : switch (variant % 2) {
			case 0 -> "gabled";
			default -> "hipped";
		};
		double roofHeight = "flat".equals(roofShape) ? 0 : Math.min(3.0, Math.max(0.8, height * 0.15));
		int levels = Math.max(1, (int)Math.round(Math.max(0, height - roofHeight) / FLOOR_HEIGHT_METERS));

		String wallMaterial = variant < 2 ? "brick" : "concrete";
		String wallColor = switch (variant) {
			case 0 -> "#c6a67a";
			case 1 -> "#d2b48c";
			case 2 -> "#aeb6bf";
			default -> "#c7ced6";
		};
		String roofColor = variant % 2 == 0 ? "#8f5f4b" : "#59636f";

		Map<String, String> tags = new LinkedHashMap<>();
		tags.put("building", "yes");
		tags.put("height", format(height));
		tags.put("building:levels", Integer.toString(levels));
		tags.put("roof:shape", roofShape);
		if (roofHeight > 0) tags.put("roof:height", format(roofHeight));
		tags.put("building:material", wallMaterial);
		tags.put("roof:material", "concrete");
		tags.put("building:colour", wallColor);
		tags.put("roof:colour", roofColor);
		return TagSet.of(tags);
	}

	private static String format(double value) {
		return String.format(Locale.ROOT, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
	}

}
