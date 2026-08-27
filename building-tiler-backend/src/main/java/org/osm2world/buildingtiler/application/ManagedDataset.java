package org.osm2world.buildingtiler.application;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.gis.DatasetInspection;

public final class ManagedDataset {

	private final UUID id;
	private final Instant createdAt;
	private final Path workDirectory;
	private final String originalFileName;
	private volatile Instant lastAccessedAt;
	private volatile DatasetStatus status;
	private volatile DatasetInspection inspection;
	private volatile HeightMapping heightMapping;

	ManagedDataset(UUID id, Path workDirectory, String originalFileName) {
		this.id = id;
		this.createdAt = Instant.now();
		this.lastAccessedAt = createdAt;
		this.workDirectory = workDirectory;
		this.originalFileName = originalFileName;
		this.status = DatasetStatus.UPLOADING;
	}

	public UUID id() { return id; }
	public Instant createdAt() { return createdAt; }
	public Instant lastAccessedAt() { return lastAccessedAt; }
	public DatasetStatus status() { return status; }
	public DatasetInspection inspection() { return inspection; }
	public HeightMapping heightMapping() { return heightMapping; }
	Path workDirectory() { return workDirectory; }
	String originalFileName() { return originalFileName; }

	void status(DatasetStatus value) { status = value; touch(); }
	void ready(DatasetInspection value) { inspection = value; status = DatasetStatus.READY; touch(); }
	void heightMapping(HeightMapping value) { heightMapping = value; touch(); }
	void touch() { lastAccessedAt = Instant.now(); }
}
