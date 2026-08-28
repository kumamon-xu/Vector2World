package org.osm2world.buildingtiler.gis;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeZipExtractorTest {

	@TempDir Path temporaryDirectory;

	@Test
	void fallsBackToGb18030ForLegacyChineseEntryNames() throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("建筑面.shp", new byte[] { 1, 2, 3 });
		entries.put("建筑面.dbf", new byte[] { 4, 5, 6 });
		var extraction = SafeZipExtractor.extract(
				writeZip(entries, Charset.forName("GB18030")), temporaryDirectory.resolve("legacy"),
				UploadLimits.defaults());

		assertEquals("GB18030", extraction.entryNameEncoding());
		assertEquals(true, extraction.legacyEncodingFallback());
		assertEquals(true, Files.isRegularFile(temporaryDirectory.resolve("legacy/建筑面.shp")));
	}

	@Test
	void rejectsZipSlipBeforeWritingOutsideDatasetDirectory() throws Exception {
		Path zip = writeZip(Map.of("../escape.shp", "bad".getBytes(UTF_8)));
		DatasetImportException exception = assertThrows(DatasetImportException.class,
				() -> SafeZipExtractor.extract(zip, temporaryDirectory.resolve("out"), UploadLimits.defaults()));
		assertEquals(DatasetErrorCode.ZIP_PATH_TRAVERSAL, exception.code());
		assertEquals(false, Files.exists(temporaryDirectory.resolve("escape.shp")));
	}

	@Test
	void rejectsCaseCollidingEntries() throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("layer.shp", new byte[] { 1 });
		entries.put("LAYER.SHP", new byte[] { 2 });
		DatasetImportException exception = assertThrows(DatasetImportException.class,
				() -> SafeZipExtractor.extract(writeZip(entries), temporaryDirectory.resolve("out"),
						UploadLimits.defaults()));
		assertEquals(DatasetErrorCode.ZIP_DUPLICATE_ENTRY, exception.code());
	}

	@Test
	void enforcesActualUncompressedBytesAndCompressionRatio() throws Exception {
		byte[] compressible = new byte[50_000];
		UploadLimits strictRatio = new UploadLimits(100_000, 100_000, 10, 2, Duration.ofHours(1));
		DatasetImportException ratio = assertThrows(DatasetImportException.class,
				() -> SafeZipExtractor.extract(writeZip(Map.of("bomb.dbf", compressible)),
						temporaryDirectory.resolve("ratio"), strictRatio));
		assertEquals(DatasetErrorCode.ZIP_COMPRESSION_RATIO_EXCEEDED, ratio.code());

		UploadLimits strictSize = new UploadLimits(100_000, 5, 10, 1000, Duration.ofHours(1));
		DatasetImportException size = assertThrows(DatasetImportException.class,
				() -> SafeZipExtractor.extract(writeZip(Map.of("large.dbf", new byte[10])),
						temporaryDirectory.resolve("size"), strictSize));
		assertEquals(DatasetErrorCode.ZIP_UNCOMPRESSED_LIMIT_EXCEEDED, size.code());
	}

	@Test
	void requiresExplicitSelectionForMultipleShapefileLayersAndEnforcesEntryCount() throws Exception {
		Path zip = writeZip(Map.of("one.shp", new byte[] { 1 }, "two.shp", new byte[] { 2 }));
		var extraction = SafeZipExtractor.extract(zip, temporaryDirectory.resolve("multi"), UploadLimits.defaults());
		DatasetImportException ambiguous = assertThrows(DatasetImportException.class,
				() -> SafeZipExtractor.selectShapefile(extraction, null));
		assertEquals(DatasetErrorCode.LAYER_SELECTION_REQUIRED, ambiguous.code());
		assertEquals("two.shp", SafeZipExtractor.selectShapefile(extraction, "two").getFileName().toString());

		UploadLimits oneEntry = new UploadLimits(1000, 1000, 1, 100, Duration.ofHours(1));
		DatasetImportException limit = assertThrows(DatasetImportException.class,
				() -> SafeZipExtractor.extract(zip, temporaryDirectory.resolve("entries"), oneEntry));
		assertEquals(DatasetErrorCode.ZIP_ENTRY_LIMIT_EXCEEDED, limit.code());
	}

	private Path writeZip(Map<String, byte[]> entries) throws Exception {
		return writeZip(entries, UTF_8);
	}

	private Path writeZip(Map<String, byte[]> entries, Charset charset) throws Exception {
		Path path = temporaryDirectory.resolve("fixture-" + System.nanoTime() + ".zip");
		try (var output = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(output, charset)) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		}
		return path;
	}
}
