package org.osm2world.buildingtiler.gis;

import java.io.IOException;
import java.nio.file.Path;

import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.domain.HeightUnit;

/** Compatibility port for the M0 generation pipeline; all reads use the M1 import core. */
public final class ShapefileBuildingReader implements DatasetReader {

	private final ShapefileDatasetReader delegate = new ShapefileDatasetReader();

	@Override
	public DatasetReadResult read(Path input, String heightField) throws IOException {
		return delegate.inspect(input, ImportOptions.defaults())
				.materialize(new HeightMapping(heightField, HeightUnit.M));
	}
}
