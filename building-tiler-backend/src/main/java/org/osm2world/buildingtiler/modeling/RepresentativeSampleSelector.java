package org.osm2world.buildingtiler.modeling;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.osm2world.buildingtiler.domain.BuildingFeature;
import org.osm2world.buildingtiler.domain.FootprintMetrics;
import org.osm2world.buildingtiler.domain.ModelingConfig;

public final class RepresentativeSampleSelector {

	private final FootprintAnalyzer footprints;
	private final StableStyleHash hashes;

	public RepresentativeSampleSelector() {
		this(new FootprintAnalyzer(), new StableStyleHash());
	}

	public RepresentativeSampleSelector(FootprintAnalyzer footprints, StableStyleHash hashes) {
		this.footprints = footprints;
		this.hashes = hashes;
	}

	public Selection select(List<BuildingFeature> features, ModelingConfig config) {
		if (features == null || config == null) throw new IllegalArgumentException("Features and config are required");
		List<Candidate> candidates = features.stream().map(feature -> new Candidate(feature,
				footprints.analyze(feature.geometryWgs84(), config.footprintThresholds()),
				score(feature, config))).toList();
		if (candidates.isEmpty()) return new Selection(List.of(), Map.of(), StableStyleHash.hash("empty"));
		double largeThreshold = candidates.stream()
				.sorted(Comparator.comparingDouble((Candidate value) -> value.metrics().areaSquareMeters()).reversed())
				.skip(Math.max(0, candidates.size() / 10 - 1L)).findFirst()
				.map(value -> value.metrics().areaSquareMeters()).orElse(Double.POSITIVE_INFINITY);

		EnumMap<Bucket, List<Candidate>> buckets = new EnumMap<>(Bucket.class);
		for (Bucket bucket : Bucket.values()) buckets.put(bucket, new ArrayList<>());
		for (Candidate candidate : candidates) {
			double height = candidate.feature().heightMeters();
			if (height < 15) buckets.get(Bucket.LOW).add(candidate);
			else if (height < 50) buckets.get(Bucket.MEDIUM).add(candidate);
			else buckets.get(Bucket.HIGH).add(candidate);
			if (candidate.metrics().areaSquareMeters() >= largeThreshold) buckets.get(Bucket.LARGE).add(candidate);
			if (candidate.metrics().irregular()) buckets.get(Bucket.IRREGULAR).add(candidate);
		}
		Comparator<Candidate> stableOrder = Comparator.comparing(Candidate::score)
				.thenComparing(value -> hashes.featureKey(value.feature()));
		buckets.values().forEach(values -> values.sort(stableOrder));
		List<Candidate> all = new ArrayList<>(candidates);
		all.sort(stableOrder);

		int maximum = Math.min(config.previewSampleSize(), candidates.size());
		Set<String> selectedKeys = new LinkedHashSet<>();
		List<BuildingFeature> selected = new ArrayList<>(maximum);
		Map<String, List<String>> coverage = new LinkedHashMap<>();
		for (Bucket bucket : Bucket.values()) {
			List<String> ids = new ArrayList<>();
			for (Candidate candidate : buckets.get(bucket)) {
				String key = hashes.featureKey(candidate.feature());
				if (selectedKeys.add(key)) {
					selected.add(candidate.feature());
					ids.add(candidate.feature().id());
					break;
				}
			}
			coverage.put(bucket.name(), List.copyOf(ids));
		}
		for (Candidate candidate : all) {
			if (selected.size() >= maximum) break;
			if (selectedKeys.add(hashes.featureKey(candidate.feature()))) selected.add(candidate.feature());
		}
		selected.sort(Comparator.comparing(feature -> hashes.featureKey(feature)));
		String selectionHash = StableStyleHash.hash(selected.stream().map(hashes::featureKey)
				.reduce(config.ruleVersion().value() + ":" + config.variantSeed(), (left, right) -> left + "\n" + right));
		return new Selection(selected, Map.copyOf(coverage), selectionHash);
	}

	private String score(BuildingFeature feature, ModelingConfig config) {
		return StableStyleHash.hash(hashes.featureKey(feature) + ":" + config.variantSeed());
	}

	public enum Bucket { LOW, MEDIUM, HIGH, LARGE, IRREGULAR }

	private record Candidate(BuildingFeature feature, FootprintMetrics metrics, String score) {}

	public record Selection(List<BuildingFeature> features, Map<String, List<String>> bucketCoverage,
			String selectionHash) {
		public Selection {
			features = List.copyOf(features);
			bucketCoverage = Map.copyOf(bucketCoverage);
		}
	}
}
