package org.osm2world.buildingtiler.domain;

public record ImportIssue(Severity severity, String code, String message, long count) {

	public enum Severity { WARNING, ERROR }

	public ImportIssue {
		if (severity == null) throw new IllegalArgumentException("Issue severity is required");
		if (code == null || code.isBlank()) throw new IllegalArgumentException("Issue code must not be blank");
		if (message == null || message.isBlank()) throw new IllegalArgumentException("Issue message must not be blank");
		if (count <= 0) throw new IllegalArgumentException("Issue count must be positive");
	}
}
