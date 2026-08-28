package org.osm2world.buildingtiler.gis;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.osm2world.buildingtiler.domain.BuildingPartId;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/** Disk-backed, immutable normalized feature list used by managed production datasets. */
final class NormalizedFeatureStore {

	private static final int MAGIC = 0x56325746; // V2WF
	private static final int VERSION = 1;
	private static final int MAX_RECORD_COMPONENT_BYTES = 256 * 1024 * 1024;
	private static final Gson GSON = new Gson();
	private static final java.lang.reflect.Type ATTRIBUTES_TYPE =
			new TypeToken<Map<String, Object>>() {}.getType();

	private NormalizedFeatureStore() {}

	static List<SourceBuildingFeature> write(Path target, List<SourceBuildingFeature> features,
			long maximumBytes, ImportDeadline deadline) throws IOException {
		if (features instanceof StoreList) return features;
		try (FeatureSink sink = streaming(target, maximumBytes, deadline)) {
			for (SourceBuildingFeature feature : features) sink.add(feature);
			return sink.finish();
		}
	}

	static FeatureSink memory() {
		return new FeatureSink() {
			private final List<SourceBuildingFeature> features = new ArrayList<>();
			@Override public void add(SourceBuildingFeature feature) { features.add(feature); }
			@Override public int size() { return features.size(); }
			@Override public List<SourceBuildingFeature> finish() { return List.copyOf(features); }
			@Override public void close() { /* no external resources */ }
		};
	}

	static FeatureSink streaming(Path target, long maximumBytes, ImportDeadline deadline) throws IOException {
		try {
			return new StreamingSink(target, maximumBytes, deadline);
		} catch (DatasetImportException exception) {
			throw exception;
		} catch (IOException exception) {
			throw storageFailure("create", exception);
		}
	}

	interface FeatureSink extends AutoCloseable {
		void add(SourceBuildingFeature feature) throws IOException;
		int size();
		List<SourceBuildingFeature> finish() throws IOException;
		@Override void close() throws IOException;
	}

	static boolean isStoreBacked(List<SourceBuildingFeature> features) {
		return features instanceof StoreList;
	}

	private static byte[] encode(SourceBuildingFeature feature, WKBWriter geometryWriter) throws IOException {
		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			 DataOutputStream output = new DataOutputStream(bytes)) {
			writeBytes(output, feature.id().getBytes(UTF_8));
			writeBytes(output, geometryWriter.write(feature.geometryWgs84()));
			writeBytes(output, GSON.toJson(feature.properties()).getBytes(UTF_8));
			writeBytes(output, feature.sourceGeometryType().getBytes(UTF_8));
			output.writeBoolean(feature.repaired());
			output.flush();
			return bytes.toByteArray();
		}
	}

	private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
		output.writeInt(value.length);
		output.write(value);
	}

	private static SourceBuildingFeature decode(DataInput input) throws IOException {
		try {
			String id = new String(readBytes(input), UTF_8);
			Geometry geometry = new WKBReader().read(readBytes(input));
			geometry.setSRID(4326);
			Map<String, Object> properties = GSON.fromJson(new String(readBytes(input), UTF_8), ATTRIBUTES_TYPE);
			String geometryType = new String(readBytes(input), UTF_8);
			boolean repaired = input.readBoolean();
			int count = geometry instanceof MultiPolygon multi ? multi.getNumGeometries() : 1;
			List<BuildingPartId> parts = new ArrayList<>(count);
			for (int index = 0; index < count; index++) parts.add(new BuildingPartId(id, index));
			return new SourceBuildingFeature(id, geometry, properties, parts, geometryType, repaired);
		} catch (ParseException | RuntimeException exception) {
			throw new IOException("Invalid normalized feature record: " + exception.getMessage(), exception);
		}
	}

	private static byte[] readBytes(DataInput input) throws IOException {
		int length = input.readInt();
		if (length < 0 || length > MAX_RECORD_COMPONENT_BYTES) {
			throw new IOException("Invalid normalized feature component length " + length);
		}
		byte[] result = new byte[length];
		input.readFully(result);
		return result;
	}

	static final class ReadException extends RuntimeException {
		ReadException(IOException cause) { super(cause); }
	}

	private static final class StreamingSink implements FeatureSink {
		private final Path target;
		private final Path temporary;
		private final long maximumBytes;
		private final ImportDeadline deadline;
		private final WKBWriter geometryWriter = new WKBWriter(2, true);
		private RandomAccessFile output;
		private long[] offsets = new long[1024];
		private int size;
		private boolean finished;

		StreamingSink(Path target, long maximumBytes, ImportDeadline deadline) throws IOException {
			this.target = target.toAbsolutePath().normalize();
			this.maximumBytes = maximumBytes;
			this.deadline = deadline;
			Files.createDirectories(this.target.getParent());
			temporary = Files.createTempFile(this.target.getParent(), this.target.getFileName().toString(), ".tmp");
			output = new RandomAccessFile(temporary.toFile(), "rw");
			output.writeInt(MAGIC);
			output.writeInt(VERSION);
			output.writeInt(0);
		}

		@Override public void add(SourceBuildingFeature feature) throws IOException {
			try {
				if (finished || output == null) throw new IllegalStateException("Feature store sink is closed");
				deadline.check("normalized feature store write");
				if (size == offsets.length) offsets = Arrays.copyOf(offsets, Math.multiplyExact(size, 2));
				offsets[size++] = output.getFilePointer();
				byte[] record = encode(feature, geometryWriter);
				output.writeInt(record.length);
				output.write(record);
				if (output.getFilePointer() > maximumBytes) {
					throw new DatasetImportException(DatasetErrorCode.IMPORT_RESOURCE_LIMIT,
							"Normalized feature store exceeds dataset quota of " + maximumBytes + " bytes");
				}
			} catch (DatasetImportException exception) {
				throw exception;
			} catch (IOException exception) {
				throw storageFailure("write", exception);
			}
		}

		@Override public int size() { return size; }

		@Override public List<SourceBuildingFeature> finish() throws IOException {
			try {
				if (finished) return new StoreList(target, Arrays.copyOf(offsets, size));
				if (output == null) throw new IllegalStateException("Feature store sink is closed");
				output.seek(8);
				output.writeInt(size);
				output.getFD().sync();
				output.close();
				output = null;
				try {
					Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING);
				} catch (IOException atomicMoveFailure) {
					try {
						Files.move(temporary, target, REPLACE_EXISTING);
					} catch (IOException fallbackFailure) {
						fallbackFailure.addSuppressed(atomicMoveFailure);
						throw fallbackFailure;
					}
				}
				finished = true;
				offsets = Arrays.copyOf(offsets, size);
				return new StoreList(target, offsets);
			} catch (DatasetImportException exception) {
				throw exception;
			} catch (IOException exception) {
				throw storageFailure("finalize", exception);
			}
		}

		@Override public void close() throws IOException {
			IOException failure = null;
			if (output != null) {
				try { output.close(); }
				catch (IOException exception) { failure = exception; }
				output = null;
			}
			if (!finished) {
				try { Files.deleteIfExists(temporary); }
				catch (IOException exception) {
					if (failure == null) failure = exception;
					else failure.addSuppressed(exception);
				}
			}
			if (failure != null) throw failure;
		}
	}

	private static DatasetImportException storageFailure(String operation, IOException exception) {
		return new DatasetImportException(DatasetErrorCode.STORAGE_UNAVAILABLE,
				"Normalized feature store cannot " + operation + " data: " + exception.getMessage(), exception);
	}

	private static final class StoreList extends AbstractList<SourceBuildingFeature> {
		private final Path file;
		private final long[] offsets;

		StoreList(Path file, long[] offsets) {
			this.file = file;
			this.offsets = offsets;
		}

		@Override public int size() { return offsets.length; }

		@Override public SourceBuildingFeature get(int index) {
			if (index < 0 || index >= offsets.length) throw new IndexOutOfBoundsException(index);
			try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
				input.seek(offsets[index]);
				int length = input.readInt();
				if (length < 0 || length > MAX_RECORD_COMPONENT_BYTES * 3L) {
					throw new IOException("Invalid normalized feature record length " + length);
				}
				byte[] record = new byte[length];
				input.readFully(record);
				try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(record))) {
					return decode(data);
				}
			} catch (IOException exception) {
				throw new ReadException(exception);
			}
		}

		@Override public Iterator<SourceBuildingFeature> iterator() {
			try {
				DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)));
				if (input.readInt() != MAGIC || input.readInt() != VERSION || input.readInt() != offsets.length) {
					input.close();
					throw new IOException("Invalid normalized feature store header");
				}
				return new Iterator<>() {
					private int index;
					private boolean closed;

					@Override public boolean hasNext() {
						boolean result = index < offsets.length;
						if (!result) close();
						return result;
					}

					@Override public SourceBuildingFeature next() {
						if (!hasNext()) throw new NoSuchElementException();
						try {
							int length = input.readInt();
							if (length < 0 || length > MAX_RECORD_COMPONENT_BYTES * 3L) {
								throw new IOException("Invalid normalized feature record length " + length);
							}
							byte[] record = new byte[length];
							input.readFully(record);
							index++;
							if (index == offsets.length) close();
							try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(record))) {
								return decode(data);
							}
						} catch (EOFException exception) {
							close();
							throw new ReadException(new IOException("Truncated normalized feature store", exception));
						} catch (IOException exception) {
							close();
							throw new ReadException(exception);
						}
					}

					private void close() {
						if (closed) return;
						closed = true;
						try { input.close(); }
						catch (IOException ignored) { /* read failure is reported by the active operation */ }
					}
				};
			} catch (IOException exception) {
				throw new ReadException(exception);
			}
		}
	}
}
