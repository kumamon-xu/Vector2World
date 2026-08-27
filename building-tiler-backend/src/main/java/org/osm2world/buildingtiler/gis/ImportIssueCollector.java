package org.osm2world.buildingtiler.gis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.osm2world.buildingtiler.domain.ImportIssue;
import org.osm2world.buildingtiler.domain.ImportIssue.Severity;

final class ImportIssueCollector {

	private final Map<String, MutableIssue> issues = new LinkedHashMap<>();

	void warning(String code, String message) { add(Severity.WARNING, code, message); }
	void error(String code, String message) { add(Severity.ERROR, code, message); }

	private void add(Severity severity, String code, String message) {
		String key = severity + "\u0000" + code + "\u0000" + message;
		issues.computeIfAbsent(key, ignored -> new MutableIssue(severity, code, message)).count++;
	}

	List<ImportIssue> result() {
		return issues.values().stream()
				.map(issue -> new ImportIssue(issue.severity, issue.code, issue.message, issue.count))
				.toList();
	}

	private static final class MutableIssue {
		private final Severity severity;
		private final String code;
		private final String message;
		private long count;
		private MutableIssue(Severity severity, String code, String message) {
			this.severity = severity;
			this.code = code;
			this.message = message;
		}
	}
}
