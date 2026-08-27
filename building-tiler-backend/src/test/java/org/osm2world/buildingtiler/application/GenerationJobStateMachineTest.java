package org.osm2world.buildingtiler.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
	import java.util.ArrayList;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.domain.HeightUnit;
import org.osm2world.buildingtiler.domain.InvalidHeightPolicy;
import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.domain.TilingConfig;

class GenerationJobStateMachineTest {

	@Test
	void acceptsOnlyTheWhitelistedHappyPathAndMakesDuplicateEventsIdempotent() {
		ManagedGenerationJob job = job();
		job.transition(GenerationJobState.VALIDATING, "validate");
		job.transition(GenerationJobState.VALIDATING, "duplicate");
		job.transition(GenerationJobState.PREPARING, "prepare");
		job.transition(GenerationJobState.TILING, "tile");
		job.transition(GenerationJobState.MODELING, "model");
		job.transition(GenerationJobState.BUILDING_TILESET, "tree");
		job.transition(GenerationJobState.VALIDATING_RESULT, "validate result");
		assertEquals(GenerationJobState.VALIDATING_RESULT, job.state());
		assertEquals(7, job.eventsAfter(0).size());
	}

	@Test
	void rejectsIllegalTransitionAndDecreasingProgress() {
		ManagedGenerationJob job = job();
		assertThrows(IllegalStateException.class,
				() -> job.transition(GenerationJobState.MODELING, "skip"));
		job.transition(GenerationJobState.VALIDATING, "validate");
		job.progress(1, 3, "one");
		assertThrows(IllegalArgumentException.class, () -> job.progress(0, 3, "backwards"));
	}

	@Test
	void cancelIsConcurrentSafeAndIdempotent() throws Exception {
		ManagedGenerationJob job = job();
		var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
		try {
			var calls = java.util.stream.IntStream.range(0, 20)
					.<java.util.concurrent.Callable<Boolean>>mapToObj(index -> job::cancel).toList();
			long changed = executor.invokeAll(calls).stream().filter(future -> {
				try { return future.get(); }
				catch (Exception exception) { throw new IllegalStateException(exception); }
			}).count();
			assertEquals(1, changed);
			assertTrue(job.cancellationRequested());
			assertEquals(GenerationJobState.CANCELLED, job.state());
			assertFalse(job.cancel());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void replayStartsAfterLastEventIdAndContinuesWithLiveEvents() {
		ManagedGenerationJob job = job();
		job.transition(GenerationJobState.VALIDATING, "validate");
		long checkpoint = job.eventsAfter(0).get(0).id();
		var received = new ArrayList<GenerationJobEvent>();
		job.replayAndSubscribe(checkpoint, received::add);
		job.heartbeat();
		job.cancel();
		assertTrue(received.size() >= 3);
		assertTrue(received.stream().allMatch(event -> event.id() > checkpoint));
		for (int index = 1; index < received.size(); index++) {
			assertTrue(received.get(index).id() > received.get(index - 1).id());
		}
		assertEquals(GenerationJobState.CANCELLED, received.get(received.size() - 1).state());
	}

	private static ManagedGenerationJob job() {
		Instant now = Instant.now();
		return new ManagedGenerationJob(UUID.randomUUID(), new GenerationJobSpec("dataset",
				new HeightMapping("Elevation", HeightUnit.M, InvalidHeightPolicy.SKIP, 10_000),
				ModelingConfig.defaults(), TilingConfig.defaults(2, 8)), Path.of("job"), now, now.plusSeconds(60));
	}
}
