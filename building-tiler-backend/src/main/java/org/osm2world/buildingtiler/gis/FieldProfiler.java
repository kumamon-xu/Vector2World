package org.osm2world.buildingtiler.gis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.osm2world.buildingtiler.domain.FieldMetadata;
import org.osm2world.buildingtiler.domain.HeightCandidate;
import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.domain.HeightUnit;

final class FieldProfiler {

	private static final int SAMPLE_LIMIT = 5;
	private final Map<String, FieldState> fields = new LinkedHashMap<>();
	private long rowCount;

	void accept(Map<String, Object> properties) {
		rowCount++;
		for (FieldState state : fields.values()) {
			if (!properties.containsKey(state.name)) state.nullOrEmpty++;
		}
		for (Map.Entry<String, Object> entry : properties.entrySet()) {
			FieldState state = fields.get(entry.getKey());
			if (state == null) {
				state = new FieldState(entry.getKey());
				state.nullOrEmpty = rowCount - 1;
				fields.put(entry.getKey(), state);
			}
			state.present++;
			Object value = entry.getValue();
			if (value == null || value.toString().isBlank()) {
				state.nullOrEmpty++;
				continue;
			}
			state.types.add(typeName(value));
			if (state.samples.size() < SAMPLE_LIMIT) state.samples.add(value.toString());
			var parsed = HeightValueParser.parse(value, new HeightMapping(state.name, HeightUnit.M));
			state.heightStats.accept(parsed);
			if (parsed.status() != HeightValueParser.Status.NON_NUMERIC
					&& parsed.status() != HeightValueParser.Status.NULL_OR_EMPTY) state.numeric++;
		}
	}

	List<FieldMetadata> metadata() {
		return fields.values().stream()
				.map(state -> new FieldMetadata(state.name, mergedType(state.types), state.present,
						state.nullOrEmpty, state.numeric, List.copyOf(state.samples)))
				.toList();
	}

	List<HeightCandidate> heightCandidates() {
		List<HeightCandidate> candidates = new ArrayList<>();
		for (FieldState state : fields.values()) {
			var quality = state.heightStats.result();
			if (state.numeric == 0) continue;
			double validRatio = rowCount == 0 ? 0 : (double) quality.valid() / rowCount;
			double nameSignal = heightNameSignal(state.name);
			double score = Math.min(1.0, 0.75 * validRatio + 0.25 * nameSignal);
			candidates.add(new HeightCandidate(state.name, score, quality));
		}
		candidates.sort(Comparator.comparingDouble(HeightCandidate::score).reversed()
				.thenComparing(HeightCandidate::fieldName));
		return List.copyOf(candidates);
	}

	private static String typeName(Object value) {
		if (value instanceof Number) return "Number";
		if (value instanceof Boolean) return "Boolean";
		return "String";
	}

	private static String mergedType(LinkedHashSet<String> types) {
		if (types.isEmpty()) return "Null";
		return types.size() == 1 ? types.iterator().next() : "Mixed";
	}

	private static double heightNameSignal(String name) {
		String normalized = name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
		if (normalized.equals("height") || normalized.equals("elevation")
				|| normalized.equals("buildingheight") || normalized.equals("高度")
				|| normalized.equals("建筑高度")) return 1.0;
		if (normalized.contains("height") || normalized.contains("elev") || normalized.contains("高度")) return 0.7;
		return 0.0;
	}

	private static final class FieldState {
		private final String name;
		private long present;
		private long nullOrEmpty;
		private long numeric;
		private final LinkedHashSet<String> types = new LinkedHashSet<>();
		private final LinkedHashSet<String> samples = new LinkedHashSet<>();
		private final HeightStatisticsAccumulator heightStats = new HeightStatisticsAccumulator();

		private FieldState(String name) { this.name = name; }
	}
}
