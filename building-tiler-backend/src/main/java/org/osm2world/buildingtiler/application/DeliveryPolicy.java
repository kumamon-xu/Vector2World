package org.osm2world.buildingtiler.application;

/** Controls whether a job may publish an explicitly incomplete result. */
public record DeliveryPolicy(
		boolean allowPartialResult,
		int maxFailedTiles,
		double maxFailedTileRatio,
		int maxFailedBuildings,
		double maxFailedBuildingRatio) {

	public DeliveryPolicy {
		if (maxFailedTiles < 0 || maxFailedBuildings < 0
				|| !validRatio(maxFailedTileRatio) || !validRatio(maxFailedBuildingRatio)) {
			throw new IllegalArgumentException("Delivery failure limits must be non-negative and ratios must be within [0, 1]");
		}
		if (!allowPartialResult && (maxFailedTiles != 0 || maxFailedBuildings != 0
				|| maxFailedTileRatio != 0 || maxFailedBuildingRatio != 0)) {
			throw new IllegalArgumentException("Failure limits require allowPartialResult=true");
		}
	}

	public static DeliveryPolicy requireComplete() {
		return new DeliveryPolicy(false, 0, 0, 0, 0);
	}

	public static DeliveryPolicy allowPartial(int maxFailedTiles, double maxFailedTileRatio,
			int maxFailedBuildings, double maxFailedBuildingRatio) {
		return new DeliveryPolicy(true, maxFailedTiles, maxFailedTileRatio,
				maxFailedBuildings, maxFailedBuildingRatio);
	}

	private static boolean validRatio(double value) {
		return Double.isFinite(value) && value >= 0 && value <= 1;
	}
}
