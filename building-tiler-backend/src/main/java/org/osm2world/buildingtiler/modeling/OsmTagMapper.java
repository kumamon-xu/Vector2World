package org.osm2world.buildingtiler.modeling;

import java.util.LinkedHashMap;
import java.util.Map;

import org.osm2world.buildingtiler.domain.StyledBuilding;

public final class OsmTagMapper {

	public static final String RULE_VERSION = org.osm2world.buildingtiler.domain.RuleVersion.CURRENT.value();

	public TagMapping toTags(StyledBuilding building) {
		var style = building.style();
		Map<String, String> tags = new LinkedHashMap<>();
		tags.put("building", "yes");
		tags.put("height", format(style.heightMeters()));
		tags.put("building:levels", Integer.toString(style.levels()));
		tags.put("roof:shape", style.roofShape());
		if (style.roofHeightMeters() > 0) tags.put("roof:height", format(style.roofHeightMeters()));
		tags.put("building:material", style.wallMaterial());
		tags.put("roof:material", style.roofMaterial());
		tags.put("building:colour", style.wallColor());
		tags.put("roof:colour", style.roofColor());
		tags.put("window", style.windows() ? "yes" : "no");
		return new TagMapping(tags, style.provenance(), style.outputHash());
	}

	private static String format(double value) {
		return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
	}

	public record TagMapping(Map<String, String> tags, Map<String, String> provenance, String ruleOutputHash) {
		public TagMapping {
			tags = Map.copyOf(tags);
			provenance = Map.copyOf(provenance);
		}
	}

}
