package org.osm2world.buildingtiler.gis;

import java.io.IOException;
import java.nio.file.Path;

public final class UploadInspector {
	private UploadInspector() {}
	public static UploadFormat detect(String originalName, String contentType, Path storedFile) throws IOException {
		return UploadFormatDetector.detect(originalName, contentType, storedFile);
	}
}
