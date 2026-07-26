package org.osm2world.map_data.creation;

import static org.junit.Assert.assertEquals;
import static org.osm2world.util.test.TestFileUtil.getTestFile;

import java.io.File;
import java.io.IOException;

import org.imintel.mbtiles4j.MBTilesReadException;
import org.junit.Test;
import org.osm2world.map_data.data.MapMetadata;
import org.osm2world.math.geo.TileNumber;
import org.osm2world.util.platform.json.JsonImplementationJvm;

public class MapMetadataMbtilesUtilTest {

	static {
		JsonImplementationJvm.register();
	}

	@Test
	public void testMetadataForTile() throws MBTilesReadException, IOException {

		File tileMetadataDb = getTestFile("meta.mbtiles");

		assertEquals(new MapMetadata("DE", true),
				MapMetadataMbtilesUtil.metadataForTile(new TileNumber(13, 4401, 2827), tileMetadataDb));

	}

}