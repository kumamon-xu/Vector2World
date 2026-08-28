package org.osm2world.buildingtiler.gis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NormalizedFeatureStoreTest {

	@TempDir Path temporaryDirectory;

	@Test
	void unavailableStorePathUsesStableStorageError() throws Exception {
		Path parentFile = temporaryDirectory.resolve("not-a-directory");
		Files.writeString(parentFile, "blocked");

		DatasetImportException exception = assertThrows(DatasetImportException.class,
				() -> NormalizedFeatureStore.streaming(parentFile.resolve("features.v2w"), 1024,
						ImportOptions.defaults().newDeadline()));

		assertEquals(DatasetErrorCode.STORAGE_UNAVAILABLE, exception.code());
	}
}
