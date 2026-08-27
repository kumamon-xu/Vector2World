package org.osm2world.buildingtiler.gis;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class SafeDatasetArchives {
	private SafeDatasetArchives() {}

	public static Extraction extract(Path zip, Path destination, UploadLimits limits) throws IOException {
		var result = SafeZipExtractor.extract(zip, destination, limits);
		return new Extraction(result.files(), result.uncompressedBytes());
	}

	public static Path selectShapefile(Extraction extraction, String requestedLayer) throws IOException {
		return SafeZipExtractor.selectShapefile(
				new SafeZipExtractor.ExtractionResult(extraction.files(), extraction.uncompressedBytes()), requestedLayer);
	}

	public record Extraction(List<Path> files, long uncompressedBytes) {
		public Extraction { files = List.copyOf(files); }
	}
}
