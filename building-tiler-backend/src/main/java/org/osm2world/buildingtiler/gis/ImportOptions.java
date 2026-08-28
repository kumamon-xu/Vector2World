package org.osm2world.buildingtiler.gis;

import java.nio.charset.Charset;
import java.time.Duration;

public record ImportOptions(
		String explicitCrs,
		String selectedLayer,
		Charset dbfCharset,
		Duration timeout,
		double repairWarningAreaRatio,
		double repairRejectAreaRatio) {

	public ImportOptions {
		timeout = timeout == null ? Duration.ofMinutes(2) : timeout;
		if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Import timeout must be positive");
		if (!Double.isFinite(repairWarningAreaRatio) || repairWarningAreaRatio < 0) {
			throw new IllegalArgumentException("Repair warning ratio must not be negative");
		}
		if (!Double.isFinite(repairRejectAreaRatio) || repairRejectAreaRatio < repairWarningAreaRatio) {
			throw new IllegalArgumentException("Repair reject ratio must be at least the warning ratio");
		}
	}

	public static ImportOptions defaults() {
		return new ImportOptions(null, null, null, Duration.ofMinutes(2), 0.05, 0.50);
	}

	public ImportOptions withExplicitCrs(String crs) {
		return new ImportOptions(crs, selectedLayer, dbfCharset, timeout,
				repairWarningAreaRatio, repairRejectAreaRatio);
	}

	public ImportDeadline newDeadline() {
		return ImportDeadline.start(timeout);
	}
}
