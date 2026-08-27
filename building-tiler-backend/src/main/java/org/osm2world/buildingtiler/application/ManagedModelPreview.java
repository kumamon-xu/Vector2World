package org.osm2world.buildingtiler.application;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.tiles.ModelPreviewWriterAdapter;

public final class ManagedModelPreview {

	private final UUID id;
	private final String datasetId;
	private final Instant createdAt;
	private final Instant expiresAt;
	private final Path outputDirectory;
	private final HeightMapping heightMapping;
	private final ModelingConfig config;
	private volatile Instant lastAccessedAt;
	private volatile ModelPreviewStatus status;
	private volatile ModelPreviewWriterAdapter.PreviewWriteResult result;
	private volatile Map<String, List<String>> bucketCoverage = Map.of();

	ManagedModelPreview(UUID id, String datasetId, Instant createdAt, Instant expiresAt,
			Path outputDirectory, HeightMapping heightMapping, ModelingConfig config) {
		this.id = id;
		this.datasetId = datasetId;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
		this.outputDirectory = outputDirectory;
		this.heightMapping = heightMapping;
		this.config = config;
		this.lastAccessedAt = createdAt;
		this.status = ModelPreviewStatus.GENERATING;
	}

	public UUID id() { return id; }
	public String datasetId() { return datasetId; }
	public Instant createdAt() { return createdAt; }
	public Instant expiresAt() { return expiresAt; }
	public Instant lastAccessedAt() { return lastAccessedAt; }
	public HeightMapping heightMapping() { return heightMapping; }
	public ModelingConfig config() { return config; }
	public ModelPreviewStatus status() { return status; }
	public ModelPreviewWriterAdapter.PreviewWriteResult result() { return result; }
	public Map<String, List<String>> bucketCoverage() { return bucketCoverage; }

	Path outputDirectory() { return outputDirectory; }
	void status(ModelPreviewStatus value) { status = value; touch(); }
	void ready(ModelPreviewWriterAdapter.PreviewWriteResult value, Map<String, List<String>> coverage) {
		result = value;
		bucketCoverage = Map.copyOf(coverage);
		status = ModelPreviewStatus.READY;
		touch();
	}
	void touch() { lastAccessedAt = Instant.now(); }
}
