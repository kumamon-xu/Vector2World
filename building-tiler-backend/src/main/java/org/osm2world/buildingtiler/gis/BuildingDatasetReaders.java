package org.osm2world.buildingtiler.gis;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

public final class BuildingDatasetReaders {

	private final DatasetReader geoJsonReader = new GeoJsonBuildingReader();
	private final DatasetReader shapefileReader = new ShapefileBuildingReader();

	public DatasetReadResult read(Path input, String heightField) throws IOException {
		String name = input.getFileName().toString().toLowerCase(Locale.ROOT);
		if (name.endsWith(".geojson") || name.endsWith(".json")) {
			return geoJsonReader.read(input, heightField);
		} else if (name.endsWith(".shp")) {
			return shapefileReader.read(input, heightField);
		}
		throw new IOException("Unsupported input format: " + input);
	}

}
