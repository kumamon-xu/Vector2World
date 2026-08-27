package org.osm2world.buildingtiler.application;

public record JobArtifact(String name, String relativePath, String mediaType, long bytes) {
	public JobArtifact {
		if (name == null || name.isBlank() || relativePath == null || relativePath.isBlank()
				|| relativePath.startsWith("/") || relativePath.contains("\\") || relativePath.contains("..")) {
			throw new IllegalArgumentException("Artifact must use a safe result-relative path");
		}
		if (bytes < 0) throw new IllegalArgumentException("Artifact bytes cannot be negative");
	}
}
