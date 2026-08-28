package org.osm2world.buildingtiler.gis;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class SafeDatasetArchives {
	private SafeDatasetArchives() {}

	public static Extraction extract(Path zip, Path destination, UploadLimits limits) throws IOException {
		return extract(zip, destination, limits, ImportDeadline.start(java.time.Duration.ofMinutes(2)));
	}

	public static Extraction extract(Path zip, Path destination, UploadLimits limits,
			ImportDeadline deadline) throws IOException {
		var result = SafeZipExtractor.extract(zip, destination, limits, deadline);
		return new Extraction(result.files(), result.uncompressedBytes(),
				result.entryNameEncoding(), result.legacyEncodingFallback());
	}

	public static Path selectShapefile(Extraction extraction, String requestedLayer) throws IOException {
		return SafeZipExtractor.selectShapefile(
				new SafeZipExtractor.ExtractionResult(extraction.files(), extraction.uncompressedBytes(),
						extraction.entryNameEncoding(), extraction.legacyEncodingFallback()), requestedLayer);
	}

	public record Extraction(List<Path> files, long uncompressedBytes,
			String entryNameEncoding, boolean legacyEncodingFallback) {
		public Extraction { files = List.copyOf(files); }
	}
}
