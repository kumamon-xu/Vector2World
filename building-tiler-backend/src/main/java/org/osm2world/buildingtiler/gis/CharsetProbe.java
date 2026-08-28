package org.osm2world.buildingtiler.gis;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class CharsetProbe {

	private CharsetProbe() {}

	static Charset utf8OrGb18030(Path input) throws IOException {
		return utf8OrGb18030(input, ImportDeadline.start(java.time.Duration.ofMinutes(2)));
	}

	static Charset utf8OrGb18030(Path input, ImportDeadline deadline) throws IOException {
		if (canDecode(input, StandardCharsets.UTF_8, deadline)) return StandardCharsets.UTF_8;
		Charset gb18030 = Charset.forName("GB18030");
		if (canDecode(input, gb18030, deadline)) return gb18030;
		throw new DatasetImportException(DatasetErrorCode.INVALID_GEOJSON,
				"GeoJSON is neither valid UTF-8 nor GB18030");
	}

	private static boolean canDecode(Path input, Charset charset, ImportDeadline deadline) throws IOException {
		var decoder = charset.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT);
		try (var reader = new InputStreamReader(Files.newInputStream(input), decoder)) {
			char[] buffer = new char[8192];
			while (reader.read(buffer) >= 0) {
				deadline.check("GeoJSON charset probe");
				/* Streaming validation intentionally retains no content. */
			}
			return true;
		} catch (CharacterCodingException exception) {
			return false;
		}
	}
}
