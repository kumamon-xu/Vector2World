package org.osm2world.buildingtiler.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class TilingConfigTest {

	@Test
	void defaultsLockEvidenceSelectedMvpDecisions() {
		TilingConfig config = TilingConfig.defaults(3, 16);
		assertEquals(15, config.zoom());
		assertEquals(List.of(2), config.lods());
		assertEquals(List.of(OutputFormat.THREE_D_TILES), config.outputFormats());
		assertEquals(0, config.crossTileBufferMeters());
	}

	@Test
	void rejectsUnverifiedLodAndExporter() {
		assertThrows(IllegalArgumentException.class, () -> new TilingConfig(15, List.of(4),
				2, 8, 1, 0, 4, List.of(OutputFormat.THREE_D_TILES)));
		assertThrows(IllegalArgumentException.class, () -> OutputFormat.parse("OBJ"));
	}

	@Test
	void validatesWorkerRetryAndCrossTileBounds() {
		assertThrows(IllegalArgumentException.class, () -> new TilingConfig(15, List.of(2),
				0, 8, 1, 0, 4, List.of(OutputFormat.THREE_D_TILES)));
		assertThrows(IllegalArgumentException.class, () -> new TilingConfig(15, List.of(2),
				2, 8, 4, 0, 4, List.of(OutputFormat.THREE_D_TILES)));
		assertThrows(IllegalArgumentException.class, () -> new TilingConfig(15, List.of(2),
				2, 8, 1, 1, 4, List.of(OutputFormat.THREE_D_TILES)));
	}
}
