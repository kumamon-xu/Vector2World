package org.osm2world.map_data.data;

import static org.junit.Assert.assertEquals;
import static org.osm2world.util.test.TestFileUtil.getTestFile;

import java.io.File;
import java.io.IOException;

import org.junit.Test;
import org.osm2world.util.platform.json.JsonImplementationJvm;

public class MapMetadataTest {

	static {
		JsonImplementationJvm.register();
	}

	@Test
	public void testMetadataFromJson() throws IOException {

		File jsonFile = getTestFile("metadata_only_locale.json");

		assertEquals(new MapMetadata("AT", null),
				MapMetadata.metadataFromJson(jsonFile));

	}

}
