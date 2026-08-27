package org.osm2world.buildingtiler.tiles;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.osm2world.buildingtiler.domain.ModelingConfig;

@FunctionalInterface
public interface TileRenderer {
	TileRenderResult render(TileWork work, List<Integer> lods, ModelingConfig config,
			Path stagingDirectory, BooleanSupplier cancelled) throws TileRenderException;
}
