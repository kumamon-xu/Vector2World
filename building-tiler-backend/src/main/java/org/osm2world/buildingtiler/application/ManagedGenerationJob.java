package org.osm2world.buildingtiler.application;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class ManagedGenerationJob {

	private static final Map<GenerationJobState, EnumSet<GenerationJobState>> TRANSITIONS = transitions();

	private final UUID id;
	private final GenerationJobSpec spec;
	private final Path workDirectory;
	private final Instant createdAt;
	private final Instant expiresAt;
	private final AtomicBoolean cancellationRequested = new AtomicBoolean();
	private final AtomicInteger activeReaders = new AtomicInteger();
	private final List<GenerationJobEvent> events = new ArrayList<>();
	private final List<Consumer<GenerationJobEvent>> subscribers = new CopyOnWriteArrayList<>();
	private final List<Future<?>> futures = new CopyOnWriteArrayList<>();
	private volatile GenerationJobState state = GenerationJobState.CREATED;
	private volatile Instant updatedAt;
	private volatile int completedTiles;
	private volatile int totalTiles;
	private volatile String error;
	private volatile GenerationJobResult result;
	private long nextEventId = 1;

	ManagedGenerationJob(UUID id, GenerationJobSpec spec, Path workDirectory, Instant createdAt, Instant expiresAt) {
		this.id = id;
		this.spec = spec;
		this.workDirectory = workDirectory;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
		this.updatedAt = createdAt;
		emit("Job created");
	}

	public UUID id() { return id; }
	public GenerationJobSpec spec() { return spec; }
	public GenerationJobState state() { return state; }
	public Instant createdAt() { return createdAt; }
	public Instant updatedAt() { return updatedAt; }
	public Instant expiresAt() { return expiresAt; }
	public int completedTiles() { return completedTiles; }
	public int totalTiles() { return totalTiles; }
	public String error() { return error; }
	public GenerationJobResult result() { return result; }
	Path workDirectory() { return workDirectory; }
	public boolean cancellationRequested() { return cancellationRequested.get(); }
	public void acquireResult() { activeReaders.incrementAndGet(); }
	public void releaseResult() { activeReaders.updateAndGet(value -> Math.max(0, value - 1)); }
	public boolean hasActiveReaders() { return activeReaders.get() > 0; }

	public synchronized void transition(GenerationJobState target, String message) {
		if (state == target) return;
		if (state.terminal()) return;
		if (!TRANSITIONS.getOrDefault(state, EnumSet.noneOf(GenerationJobState.class)).contains(target)) {
			throw new IllegalStateException("Illegal job transition " + state + " -> " + target);
		}
		state = target;
		updatedAt = Instant.now();
		emit(message);
	}

	public synchronized void progress(int completed, int total, String message) {
		if (state.terminal()) return;
		if (completed < 0 || total < 0 || completed > total || completed < completedTiles) {
			throw new IllegalArgumentException("Invalid or decreasing job progress");
		}
		completedTiles = completed;
		totalTiles = total;
		updatedAt = Instant.now();
		emit(message);
	}

	public synchronized void complete(GenerationJobResult value) {
		if (state.terminal()) return;
		result = value;
		GenerationJobState terminal = value.warnings().isEmpty() && value.failedTiles() == 0
				? GenerationJobState.COMPLETED : GenerationJobState.COMPLETED_WITH_WARNINGS;
		transition(terminal, terminal == GenerationJobState.COMPLETED
				? "Generation completed" : "Generation completed with warnings");
	}

	public synchronized void fail(String message) {
		if (state.terminal()) return;
		error = message == null ? "Generation failed" : message;
		transition(GenerationJobState.FAILED, error);
	}

	public synchronized boolean cancel() {
		if (state.terminal()) return false;
		cancellationRequested.set(true);
		for (Future<?> future : futures) future.cancel(true);
		transition(GenerationJobState.CANCELLED, "Cancellation requested");
		return true;
	}

	void track(Future<?> future) {
		if (future == null) return;
		futures.add(future);
		if (cancellationRequested()) future.cancel(true);
	}

	void untrack(Future<?> future) {
		futures.remove(future);
	}

	public synchronized List<GenerationJobEvent> eventsAfter(long eventId) {
		return events.stream().filter(event -> event.id() > eventId).toList();
	}

	public synchronized void heartbeat() {
		if (!state.terminal()) emit("heartbeat");
	}

	public void subscribe(Consumer<GenerationJobEvent> subscriber) {
		subscribers.add(subscriber);
	}

	public synchronized void replayAndSubscribe(long eventId, Consumer<GenerationJobEvent> subscriber) {
		for (GenerationJobEvent event : events) {
			if (event.id() > eventId) subscriber.accept(event);
		}
		if (!state.terminal()) subscribers.add(subscriber);
	}

	public void unsubscribe(Consumer<GenerationJobEvent> subscriber) {
		subscribers.remove(subscriber);
	}

	private synchronized void emit(String message) {
		GenerationJobEvent event = new GenerationJobEvent(nextEventId++, Instant.now(), state,
				completedTiles, totalTiles, message);
		events.add(event);
		for (Consumer<GenerationJobEvent> subscriber : subscribers) subscriber.accept(event);
	}

	private static Map<GenerationJobState, EnumSet<GenerationJobState>> transitions() {
		Map<GenerationJobState, EnumSet<GenerationJobState>> result = new EnumMap<>(GenerationJobState.class);
		result.put(GenerationJobState.CREATED, next(GenerationJobState.VALIDATING));
		result.put(GenerationJobState.VALIDATING, next(GenerationJobState.PREPARING));
		result.put(GenerationJobState.PREPARING, next(GenerationJobState.TILING));
		result.put(GenerationJobState.TILING, next(GenerationJobState.MODELING));
		result.put(GenerationJobState.MODELING, next(GenerationJobState.BUILDING_TILESET));
		result.put(GenerationJobState.BUILDING_TILESET, next(GenerationJobState.VALIDATING_RESULT));
		result.put(GenerationJobState.VALIDATING_RESULT, EnumSet.of(
				GenerationJobState.COMPLETED, GenerationJobState.COMPLETED_WITH_WARNINGS,
				GenerationJobState.FAILED, GenerationJobState.CANCELLED));
		return Map.copyOf(result);
	}

	private static EnumSet<GenerationJobState> next(GenerationJobState normal) {
		return EnumSet.of(normal, GenerationJobState.FAILED, GenerationJobState.CANCELLED);
	}
}
