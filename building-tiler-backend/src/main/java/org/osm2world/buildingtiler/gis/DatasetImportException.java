package org.osm2world.buildingtiler.gis;

import java.io.IOException;
import java.util.Map;

public final class DatasetImportException extends IOException {

	private final DatasetErrorCode code;
	private final Map<String, Object> details;

	public DatasetImportException(DatasetErrorCode code, String message) {
		this(code, message, null, Map.of());
	}

	public DatasetImportException(DatasetErrorCode code, String message, Throwable cause) {
		this(code, message, cause, Map.of());
	}

	public DatasetImportException(DatasetErrorCode code, String message, Map<String, Object> details) {
		this(code, message, null, details);
	}

	private DatasetImportException(DatasetErrorCode code, String message, Throwable cause,
			Map<String, Object> details) {
		super(message, cause);
		this.code = code;
		this.details = details == null ? Map.of() : Map.copyOf(details);
	}

	public DatasetErrorCode code() {
		return code;
	}

	public Map<String, Object> details() {
		return details;
	}
}
