package org.osm2world.buildingtiler.modeling;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.precision.GeometryPrecisionReducer;
import org.osm2world.buildingtiler.domain.BuildingFeature;
import org.osm2world.buildingtiler.domain.ModelingConfig;

public final class StableStyleHash {

	private static final PrecisionModel WGS84_PRECISION = new PrecisionModel(10_000_000);

	public String featureKey(BuildingFeature feature) {
		Geometry geometry = GeometryPrecisionReducer.reduce(feature.geometryWgs84(), WGS84_PRECISION);
		geometry.normalize();
		return hash(geometry.toText() + "\nheight=" + decimal(feature.heightMeters()));
	}

	public int variantBucket(BuildingFeature feature, ModelingConfig config, int bucketCount) {
		if (bucketCount < 1) throw new IllegalArgumentException("bucketCount must be positive");
		String value = hash(featureKey(feature) + "\n" + canonicalStyleConfig(config));
		long firstEightBytes = Long.parseUnsignedLong(value.substring(0, 16), 16);
		return (int)Long.remainderUnsigned(firstEightBytes, bucketCount);
	}

	public String outputHash(BuildingFeature feature, ModelingConfig config, String styleCanonical) {
		return hash(featureKey(feature) + "\n" + canonicalStyleConfig(config) + "\n" + styleCanonical);
	}

	public String configHash(ModelingConfig config) {
		return hash(canonicalConfig(config));
	}

	public static String hash(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static String canonicalStyleConfig(ModelingConfig config) {
		return String.join("|",
				config.ruleVersion().value(), config.roofMode().name(), config.stylePreset().value(),
				decimal(config.floorHeightMeters()), decimal(config.roofHeightRatio()),
				decimal(config.minimumRoofHeightMeters()), decimal(config.maximumRoofHeightMeters()),
				decimal(config.minimumBodyHeightMeters()), decimal(config.minimumPitchedBuildingHeightMeters()),
				decimal(config.maximumPitchedBuildingHeightMeters()), Long.toString(config.variantSeed()),
				decimal(config.footprintThresholds().minimumCompactness()),
				decimal(config.footprintThresholds().minimumConvexity()),
				decimal(config.footprintThresholds().minimumOrthogonality()),
				Integer.toString(config.footprintThresholds().maximumSimpleVertices()),
				decimal(config.footprintThresholds().maximumPitchedAspectRatio()));
	}

	private static String canonicalConfig(ModelingConfig config) {
		return canonicalStyleConfig(config)
				+ "|lod=" + config.lod()
				+ "|previewSampleSize=" + config.previewSampleSize();
	}

	static String decimal(double value) {
		return String.format(Locale.ROOT, "%.6f", value);
	}
}
