package org.osm2world.buildingtiler.application;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class ModelPreviewCleanupScheduler {

	private final ModelPreviewService previews;

	public ModelPreviewCleanupScheduler(ModelPreviewService previews) {
		this.previews = previews;
	}

	@Scheduled(fixedDelayString = "${vector2world.previews.cleanup-interval-ms:600000}",
			initialDelayString = "${vector2world.previews.cleanup-initial-delay-ms:0}")
	public void cleanup() {
		previews.cleanupExpired(Instant.now());
	}
}
