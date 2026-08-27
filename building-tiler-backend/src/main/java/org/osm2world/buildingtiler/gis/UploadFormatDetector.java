package org.osm2world.buildingtiler.gis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

final class UploadFormatDetector {

	private static final Set<String> JSON_MIME = Set.of(
			"application/geo+json", "application/json", "text/json", "application/octet-stream");
	private static final Set<String> ZIP_MIME = Set.of(
			"application/zip", "application/x-zip-compressed", "application/octet-stream");

	private UploadFormatDetector() {}

	static UploadFormat detect(String originalName, String contentType, Path storedFile)
			throws IOException {
		String name = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
		String mime = normalizeMime(contentType);
		if (name.endsWith(".geojson") || name.endsWith(".json")) {
			if (!mime.isEmpty() && !JSON_MIME.contains(mime)) mismatch(name, mime);
			if (!looksLikeJson(storedFile)) mismatch(name, "binary signature");
			return UploadFormat.GEOJSON;
		}
		if (name.endsWith(".zip")) {
			if (!mime.isEmpty() && !ZIP_MIME.contains(mime)) mismatch(name, mime);
			if (!looksLikeZip(storedFile)) mismatch(name, "binary signature");
			return UploadFormat.SHP_ZIP;
		}
		throw new DatasetImportException(DatasetErrorCode.UNSUPPORTED_FORMAT,
				"Supported upload extensions are .geojson, .json and .zip");
	}

	private static void mismatch(String name, String actual) throws DatasetImportException {
		throw new DatasetImportException(DatasetErrorCode.CONTENT_TYPE_MISMATCH,
				"File extension and content type/signature do not match: " + name + " / " + actual);
	}

	private static boolean looksLikeJson(Path file) throws IOException {
		try (InputStream input = Files.newInputStream(file)) {
			int value;
			boolean first = true;
			while ((value = input.read()) >= 0) {
				if (first && value == 0xEF) {
					int b2 = input.read();
					int b3 = input.read();
					if (b2 == 0xBB && b3 == 0xBF) { first = false; continue; }
					return false;
				}
				first = false;
				if (!Character.isWhitespace(value)) return value == '{';
			}
		}
		return false;
	}

	private static boolean looksLikeZip(Path file) throws IOException {
		try (InputStream input = Files.newInputStream(file)) {
			byte[] signature = input.readNBytes(4);
			return signature.length == 4 && signature[0] == 'P' && signature[1] == 'K'
					&& ((signature[2] == 3 && signature[3] == 4)
							|| (signature[2] == 5 && signature[3] == 6)
							|| (signature[2] == 7 && signature[3] == 8));
		}
	}

	private static String normalizeMime(String value) {
		if (value == null) return "";
		int semicolon = value.indexOf(';');
		return (semicolon < 0 ? value : value.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
	}
}
