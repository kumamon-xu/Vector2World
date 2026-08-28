package org.osm2world.buildingtiler.gis;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.nio.charset.StandardCharsets.UTF_8;

final class SafeZipExtractor {

	private static final Charset LEGACY_CHINESE_ENTRY_CHARSET = Charset.forName("GB18030");

	private SafeZipExtractor() {}

	static ExtractionResult extract(Path zip, Path destination, UploadLimits limits) throws IOException {
		return extract(zip, destination, limits, ImportDeadline.start(java.time.Duration.ofMinutes(2)));
	}

	static ExtractionResult extract(Path zip, Path destination, UploadLimits limits,
			ImportDeadline deadline) throws IOException {
		deadline.check("ZIP initialization");
		Files.createDirectory(destination);
		Path root = destination.toAbsolutePath().normalize();
		Set<String> normalizedNames = new HashSet<>();
		List<Path> files = new ArrayList<>();
		long totalBytes = 0;
		int entries = 0;

		ArchiveHandle handle = openArchive(zip);
		try (ZipFile archive = handle.archive()) {
			var iterator = archive.entries().asIterator();
			while (iterator.hasNext()) {
				deadline.check("ZIP entry enumeration");
				ZipEntry entry = iterator.next();
				entries++;
				if (entries > limits.maximumZipEntries()) {
					throw new DatasetImportException(DatasetErrorCode.ZIP_ENTRY_LIMIT_EXCEEDED,
							"ZIP contains more than " + limits.maximumZipEntries() + " entries");
				}
				String entryName = entry.getName().replace('\\', '/');
				String collisionKey = entryName.toLowerCase(Locale.ROOT);
				if (!normalizedNames.add(collisionKey)) {
					throw new DatasetImportException(DatasetErrorCode.ZIP_DUPLICATE_ENTRY,
							"ZIP contains duplicate or case-colliding entry: " + entryName);
				}
				if (entryName.isBlank() || entryName.startsWith("/") || entryName.matches("^[A-Za-z]:.*")) {
					throw traversal(entryName);
				}
				Path target = root.resolve(entryName).normalize();
				if (!target.startsWith(root)) throw traversal(entryName);
				if (entry.isDirectory()) {
					Files.createDirectories(target);
					continue;
				}
				long declaredSize = entry.getSize();
				long compressedSize = entry.getCompressedSize();
				if (declaredSize > limits.maximumZipUncompressedBytes()) uncompressedLimit(limits);
				if (declaredSize > 0 && compressedSize > 0
						&& (double) declaredSize / compressedSize > limits.maximumCompressionRatio()) {
					throw new DatasetImportException(DatasetErrorCode.ZIP_COMPRESSION_RATIO_EXCEEDED,
							"ZIP entry compression ratio exceeds " + limits.maximumCompressionRatio());
				}
				Files.createDirectories(target.getParent());
				try (InputStream input = archive.getInputStream(entry);
					 OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
					byte[] buffer = new byte[8192];
					long entryBytes = 0;
					int count;
					while ((count = input.read(buffer)) >= 0) {
						deadline.check("ZIP decompression");
						entryBytes += count;
						totalBytes += count;
						if (totalBytes > limits.maximumZipUncompressedBytes()) uncompressedLimit(limits);
						if (compressedSize > 0 && (double) entryBytes / compressedSize > limits.maximumCompressionRatio()) {
							throw new DatasetImportException(DatasetErrorCode.ZIP_COMPRESSION_RATIO_EXCEEDED,
									"ZIP entry compression ratio exceeds " + limits.maximumCompressionRatio());
						}
						output.write(buffer, 0, count);
					}
				}
				files.add(target);
			}
		} catch (DatasetImportException exception) {
			throw exception;
		} catch (java.util.zip.ZipException exception) {
			throw new DatasetImportException(DatasetErrorCode.INVALID_ZIP,
					"ZIP archive is invalid: " + exception.getMessage(), exception);
		}
		deadline.check("ZIP finalization");
		return new ExtractionResult(List.copyOf(files), totalBytes,
				handle.entryNameCharset().name(), handle.usedLegacyFallback());
	}

	private static ArchiveHandle openArchive(Path zip) throws IOException {
		try {
			return new ArchiveHandle(new ZipFile(zip.toFile(), UTF_8), UTF_8, false);
		} catch (java.util.zip.ZipException exception) {
			if (!isEntryNameDecodingFailure(exception)) throw exception;
			try {
				return new ArchiveHandle(new ZipFile(zip.toFile(), LEGACY_CHINESE_ENTRY_CHARSET),
						LEGACY_CHINESE_ENTRY_CHARSET, true);
			} catch (java.util.zip.ZipException fallbackFailure) {
				fallbackFailure.addSuppressed(exception);
				throw fallbackFailure;
			}
		}
	}

	private static boolean isEntryNameDecodingFailure(java.util.zip.ZipException exception) {
		String message = exception.getMessage();
		return message != null && message.toLowerCase(Locale.ROOT).contains("bad entry name");
	}

	static Path selectShapefile(ExtractionResult extraction, String requestedLayer) throws IOException {
		List<Path> shapefiles = extraction.files().stream()
				.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".shp"))
				.sorted().toList();
		if (shapefiles.isEmpty()) throw new DatasetImportException(DatasetErrorCode.SHAPEFILE_SIDECAR_MISSING,
				"ZIP contains no .shp file");
		if (requestedLayer == null || requestedLayer.isBlank()) {
			if (shapefiles.size() != 1) throw new DatasetImportException(DatasetErrorCode.LAYER_SELECTION_REQUIRED,
					"ZIP contains multiple shapefiles; select one explicitly",
					java.util.Map.of("layers", shapefiles.stream().map(SafeZipExtractor::baseName).toList()));
			return shapefiles.get(0);
		}
		return shapefiles.stream().filter(path -> baseName(path).equals(requestedLayer)
				|| path.getFileName().toString().equals(requestedLayer)).findFirst()
				.orElseThrow(() -> new DatasetImportException(DatasetErrorCode.LAYER_NOT_FOUND,
						"Selected shapefile layer does not exist: " + requestedLayer,
						java.util.Map.of("layers", shapefiles.stream().map(SafeZipExtractor::baseName).toList())));
	}

	private static String baseName(Path path) {
		String name = path.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot < 0 ? name : name.substring(0, dot);
	}

	private static DatasetImportException traversal(String name) {
		return new DatasetImportException(DatasetErrorCode.ZIP_PATH_TRAVERSAL,
				"ZIP entry escapes the dataset directory: " + name);
	}

	private static void uncompressedLimit(UploadLimits limits) throws DatasetImportException {
		throw new DatasetImportException(DatasetErrorCode.ZIP_UNCOMPRESSED_LIMIT_EXCEEDED,
				"ZIP expands beyond " + limits.maximumZipUncompressedBytes() + " bytes");
	}

	private record ArchiveHandle(ZipFile archive, Charset entryNameCharset, boolean usedLegacyFallback) {}

	record ExtractionResult(List<Path> files, long uncompressedBytes,
			String entryNameEncoding, boolean legacyEncodingFallback) {}
}
