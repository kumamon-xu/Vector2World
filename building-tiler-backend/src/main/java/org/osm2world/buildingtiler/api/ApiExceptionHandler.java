package org.osm2world.buildingtiler.api;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import org.osm2world.buildingtiler.gis.DatasetErrorCode;
import org.osm2world.buildingtiler.gis.DatasetImportException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public final class ApiExceptionHandler {

	@ExceptionHandler(DatasetImportException.class)
	public ResponseEntity<ApiErrorResponse> datasetError(DatasetImportException exception) {
		HttpStatus status = status(exception.code());
		return ResponseEntity.status(status).body(response(status, exception.code().name(),
				exception.getMessage(), exception.details()));
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ApiErrorResponse> multipartTooLarge(MaxUploadSizeExceededException exception) {
		return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(response(HttpStatus.PAYLOAD_TOO_LARGE,
				DatasetErrorCode.UPLOAD_TOO_LARGE.name(), "Upload exceeds the configured size limit", Map.of()));
	}

	@ExceptionHandler({ IllegalArgumentException.class, MethodArgumentNotValidException.class })
	public ResponseEntity<ApiErrorResponse> invalidRequest(Exception exception) {
		return ResponseEntity.badRequest().body(response(HttpStatus.BAD_REQUEST,
				DatasetErrorCode.INVALID_REQUEST.name(), exception.getMessage(), Map.of()));
	}

	@ExceptionHandler(IOException.class)
	public ResponseEntity<ApiErrorResponse> ioError(IOException exception) {
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response(HttpStatus.INTERNAL_SERVER_ERROR,
				DatasetErrorCode.STORAGE_UNAVAILABLE.name(), "Dataset I/O operation failed", Map.of()));
	}

	private static ApiErrorResponse response(HttpStatus status, String code, String message,
			Map<String, Object> details) {
		return new ApiErrorResponse("1.0", Instant.now(), status.value(), code, message, details);
	}

	private static HttpStatus status(DatasetErrorCode code) {
		return switch (code) {
			case DATASET_NOT_FOUND -> HttpStatus.NOT_FOUND;
			case UPLOAD_TOO_LARGE, ZIP_ENTRY_LIMIT_EXCEEDED, ZIP_UNCOMPRESSED_LIMIT_EXCEEDED,
					ZIP_COMPRESSION_RATIO_EXCEEDED -> HttpStatus.PAYLOAD_TOO_LARGE;
			case DATASET_NOT_READY -> HttpStatus.CONFLICT;
			case STORAGE_UNAVAILABLE -> HttpStatus.INTERNAL_SERVER_ERROR;
			default -> HttpStatus.BAD_REQUEST;
		};
	}
}
