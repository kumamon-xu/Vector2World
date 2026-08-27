package org.osm2world.buildingtiler.application;

import java.io.IOException;
import java.nio.file.Path;

import org.osm2world.buildingtiler.gis.BuildingDatasetReaders;
import org.osm2world.buildingtiler.gis.DatasetReadResult;
import org.osm2world.buildingtiler.modeling.OsmTagMapper;
import org.osm2world.buildingtiler.osm2world.Osm2WorldEngineAdapter;
import org.osm2world.buildingtiler.tiles.TilesetValidator;
import org.osm2world.buildingtiler.tiles.TilesetWriterAdapter;

public final class Milestone0Pipeline {

	private final BuildingDatasetReaders readers = new BuildingDatasetReaders();
	private final TilesetWriterAdapter writer = new TilesetWriterAdapter(
			new Osm2WorldEngineAdapter(new OsmTagMapper()), new TilesetValidator());

	public PipelineResult run(Path input, Path output, String heightField,
			int zoom, int lod, int maxTiles) throws IOException {
		return run(input, output, heightField, zoom, lod, maxTiles, false);
	}

	public PipelineResult run(Path input, Path output, String heightField,
			int zoom, int lod, int maxTiles, boolean clipToBounds) throws IOException {
		DatasetReadResult dataset = readers.read(input, heightField);
		TilesetWriterAdapter.GenerationResult generation = writer.write(
				dataset, output, zoom, lod, maxTiles, clipToBounds);
		return new PipelineResult(dataset, generation);
	}

	public record PipelineResult(DatasetReadResult dataset, TilesetWriterAdapter.GenerationResult generation) {}

}
