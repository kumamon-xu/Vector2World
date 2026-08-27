package org.osm2world.buildingtiler.gis;

import java.io.IOException;
import java.nio.file.Path;

public interface InspectingDatasetReader {
	DatasetInspection inspect(Path input, ImportOptions options) throws IOException;
}
