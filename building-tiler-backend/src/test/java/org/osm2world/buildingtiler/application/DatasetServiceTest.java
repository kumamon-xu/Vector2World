package org.osm2world.buildingtiler.application;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.domain.HeightUnit;
import org.osm2world.buildingtiler.gis.DatasetErrorCode;
import org.osm2world.buildingtiler.gis.DatasetImportException;
import org.osm2world.buildingtiler.gis.ImportOptions;
import org.osm2world.buildingtiler.gis.UploadLimits;

class DatasetServiceTest {

	@TempDir Path temporaryDirectory;

	@Test
	void maliciousAndDuplicateOriginalNamesCannotControlOrCollideOnDisk() throws Exception {
		DatasetService service = service(UploadLimits.defaults());
		byte[] fixture = fixture();
		ManagedDataset first = service.upload("../../same.geojson", "application/geo+json", fixture.length,
				new ByteArrayInputStream(fixture), ImportOptions.defaults());
		ManagedDataset second = service.upload("..\\..\\same.geojson", "application/json", fixture.length,
				new ByteArrayInputStream(fixture), ImportOptions.defaults());

		assertNotEquals(first.id(), second.id());
		assertNotEquals(first.workDirectory(), second.workDirectory());
		assertTrue(first.workDirectory().startsWith(temporaryDirectory.resolve("datasets")));
		assertFalse(Files.exists(temporaryDirectory.resolve("same.geojson")));
		assertEquals(2, service.size());
	}

	@Test
	void contentMismatchAndOverLimitUploadsLeaveNoDatasetDirectories() throws Exception {
		UploadLimits limits = new UploadLimits(20, 100, 10, 100, Duration.ofHours(1));
		DatasetService service = service(limits);
		DatasetImportException tooLarge = assertThrows(DatasetImportException.class,
				() -> service.upload("x.geojson", "application/json", 21,
						new ByteArrayInputStream(new byte[21]), ImportOptions.defaults()));
		assertEquals(DatasetErrorCode.UPLOAD_TOO_LARGE, tooLarge.code());

		byte[] zipSignature = new byte[] { 'P', 'K', 3, 4, 0 };
		DatasetImportException mismatch = assertThrows(DatasetImportException.class,
				() -> service.upload("x.geojson", "application/json", zipSignature.length,
						new ByteArrayInputStream(zipSignature), ImportOptions.defaults()));
		assertEquals(DatasetErrorCode.CONTENT_TYPE_MISMATCH, mismatch.code());
		assertEquals(0, service.size());
		try (var entries = Files.list(temporaryDirectory.resolve("datasets"))) {
			assertEquals(0, entries.count());
		}
	}

	@Test
	void explicitHeightMappingConvertsUnitsAndDefaultsToSkip() throws Exception {
		DatasetService service = service(UploadLimits.defaults());
		byte[] fixture = fixture();
		ManagedDataset dataset = service.upload("buildings.geojson", "application/geo+json", fixture.length,
				new ByteArrayInputStream(fixture), ImportOptions.defaults());
		assertTrue(Files.isRegularFile(dataset.workDirectory().resolve("normalized-features.v2w")));
		assertEquals(4, dataset.inspection().features().size());
		assertEquals(1, ((org.locationtech.jts.geom.Polygon)dataset.inspection().features().get(0)
				.geometryWgs84()).getNumInteriorRing());

		var result = service.materialize(dataset.id().toString(), new HeightMapping("Elevation", HeightUnit.CM));
		assertEquals(3, result.buildings().size());
		assertEquals(0.12, result.metadata().minHeightMeters(), 1e-12);
		assertEquals(0.48, result.metadata().maxHeightMeters(), 1e-12);
		assertEquals(1, result.metadata().heightQuality().invalid());
	}

	@Test
	void normalizedStoreQuotaAndCorruptionFailCleanly() throws Exception {
		byte[] fixture = fixture();
		DatasetService quotaService = service(new UploadLimits(1_000_000, 64, 100, 100,
				Duration.ofHours(1)));
		DatasetImportException quota = assertThrows(DatasetImportException.class,
				() -> quotaService.upload("quota.geojson", "application/geo+json", fixture.length,
						new ByteArrayInputStream(fixture), ImportOptions.defaults()));
		assertEquals(DatasetErrorCode.IMPORT_RESOURCE_LIMIT, quota.code());
		assertEquals(0, quotaService.size());

		DatasetService service = service(UploadLimits.defaults());
		ManagedDataset dataset = service.upload("corrupt.geojson", "application/geo+json", fixture.length,
				new ByteArrayInputStream(fixture), ImportOptions.defaults());
		Files.writeString(dataset.workDirectory().resolve("normalized-features.v2w"), "corrupt", UTF_8);
		DatasetImportException corrupt = assertThrows(DatasetImportException.class,
				() -> service.materialize(dataset.id().toString(), new HeightMapping("Elevation", HeightUnit.M)));
		assertEquals(DatasetErrorCode.STORAGE_UNAVAILABLE, corrupt.code());
	}

	@Test
	void deleteIsConcurrentSafeAndTtlCleanupRemovesStorage() throws Exception {
		DatasetService service = service(new UploadLimits(1_000_000, 1_000_000, 100, 100,
				Duration.ofSeconds(1)));
		byte[] fixture = fixture();
		ManagedDataset first = service.upload("one.geojson", "application/json", fixture.length,
				new ByteArrayInputStream(fixture), ImportOptions.defaults());
		var executor = Executors.newFixedThreadPool(2);
		try {
			var a = executor.submit(() -> service.delete(first.id().toString()));
			var b = executor.submit(() -> service.delete(first.id().toString()));
			assertEquals(1, (a.get() ? 1 : 0) + (b.get() ? 1 : 0));
		} finally {
			executor.shutdownNow();
		}
		assertFalse(Files.exists(first.workDirectory()));

		ManagedDataset expired = service.upload("two.geojson", "application/json", fixture.length,
				new ByteArrayInputStream(fixture), ImportOptions.defaults());
		assertEquals(1, service.cleanupExpired(Instant.now().plusSeconds(2)));
		assertFalse(Files.exists(expired.workDirectory()));

		Path orphan = temporaryDirectory.resolve("datasets/dataset-orphaned-after-restart");
		Files.createDirectory(orphan);
		Files.setLastModifiedTime(orphan, FileTime.from(Instant.now().minusSeconds(10)));
		assertEquals(1, service.cleanupExpired(Instant.now()));
		assertFalse(Files.exists(orphan));
	}

	@Test
	void nonDirectoryStorageRootReturnsStableError() throws Exception {
		Path rootFile = temporaryDirectory.resolve("not-a-directory");
		Files.writeString(rootFile, "blocked", UTF_8);
		DatasetService service = new DatasetService(rootFile, UploadLimits.defaults());
		DatasetImportException exception = assertThrows(DatasetImportException.class,
				() -> service.upload("x.geojson", "application/json", 2,
						new ByteArrayInputStream("{}".getBytes(UTF_8)), ImportOptions.defaults()));
		assertEquals(DatasetErrorCode.STORAGE_UNAVAILABLE, exception.code());
	}

	@Test
	void oneImportBudgetCoversUploadCopyAndMaterializationCancellation() throws Exception {
		DatasetService service = service(UploadLimits.defaults());
		byte[] fixture = fixture();
		var slow = new ByteArrayInputStream(fixture) {
			@Override public synchronized int read(byte[] buffer, int offset, int length) {
				try { Thread.sleep(10); }
				catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
				return super.read(buffer, offset, Math.min(length, 64));
			}
		};
		ImportOptions tinyBudget = new ImportOptions(null, null, null, Duration.ofMillis(2), 0.05, 0.50);
		DatasetImportException timeout = assertThrows(DatasetImportException.class,
				() -> service.upload("slow.geojson", "application/geo+json", fixture.length, slow, tinyBudget));
		assertEquals(DatasetErrorCode.IMPORT_TIMEOUT, timeout.code());
		assertEquals(0, service.size());

		ManagedDataset dataset = service.upload("normal.geojson", "application/geo+json", fixture.length,
				new ByteArrayInputStream(fixture), ImportOptions.defaults());
		Thread.currentThread().interrupt();
		try {
			DatasetImportException cancelled = assertThrows(DatasetImportException.class,
					() -> service.materialize(dataset.id().toString(),
							new HeightMapping("Elevation", HeightUnit.M)));
			assertEquals(DatasetErrorCode.IMPORT_CANCELLED, cancelled.code());
		} finally {
			Thread.interrupted();
		}
	}

	private DatasetService service(UploadLimits limits) {
		return new DatasetService(temporaryDirectory.resolve("datasets"), limits);
	}

	private byte[] fixture() throws Exception {
		return getClass().getResourceAsStream("/m0-polygons.geojson").readAllBytes();
	}
}
