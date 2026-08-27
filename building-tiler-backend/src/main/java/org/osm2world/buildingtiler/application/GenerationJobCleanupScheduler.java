package org.osm2world.buildingtiler.application;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;

public final class GenerationJobCleanupScheduler {

	private final GenerationJobService jobs;

	public GenerationJobCleanupScheduler(GenerationJobService jobs) {
		this.jobs = jobs;
	}

	@Scheduled(fixedDelayString = "${vector2world.jobs.cleanup-interval-ms:600000}",
			initialDelayString = "${vector2world.jobs.cleanup-initial-delay-ms:0}")
	public void cleanup() {
		jobs.cleanupExpired(Instant.now());
	}
}
