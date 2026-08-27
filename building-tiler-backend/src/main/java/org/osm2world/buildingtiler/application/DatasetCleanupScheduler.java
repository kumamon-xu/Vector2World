package org.osm2world.buildingtiler.application;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class DatasetCleanupScheduler {

	private final DatasetService datasets;

	public DatasetCleanupScheduler(DatasetService datasets) {
		this.datasets = datasets;
	}

	@Scheduled(fixedDelayString = "${vector2world.datasets.cleanup-interval-ms:600000}",
			initialDelayString = "${vector2world.datasets.cleanup-initial-delay-ms:0}")
	public void cleanup() {
		datasets.cleanupExpired(Instant.now());
	}
}
