package org.osm2world.buildingtiler.gis;

import java.io.IOException;
import java.nio.file.Path;

public interface DatasetReader {

	DatasetReadResult read(Path input, String heightField) throws IOException;

}
