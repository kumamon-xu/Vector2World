package org.osm2world.buildingtiler.product;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;

public record ProductDataLayout(
		Path root,
		Path config,
		Path cache,
		Path instance,
		Path datasets,
		Path previews,
		Path jobs,
		Path logs) {

	public static final int SETTINGS_SCHEMA_VERSION = 1;

	public static ProductDataLayout open(Path requestedRoot, String requestedInstanceId) throws IOException {
		Path root = (requestedRoot == null ? defaultRoot() : requestedRoot).toAbsolutePath().normalize();
		rejectUnc(root);
		Files.createDirectories(root);
		requireWritable(root);

		Path config = Files.createDirectories(root.resolve("config"));
		Properties settings = migrateSettings(config.resolve("settings.properties"));
		Path dataRoot = resolveDataRoot(root, settings.getProperty("data.root"));
		rejectUnc(dataRoot);
		Files.createDirectories(dataRoot);
		requireWritable(dataRoot);

		String instanceId = normalizeInstanceId(requestedInstanceId);
		Path instance = Files.createDirectories(dataRoot.resolve("instances").resolve(instanceId));
		Path cache = Files.createDirectories(root.resolve("cache"));
		Path datasets = Files.createDirectories(instance.resolve("datasets"));
		Path previews = Files.createDirectories(instance.resolve("previews"));
		Path jobs = Files.createDirectories(instance.resolve("jobs"));
		Path logs = Files.createDirectories(instance.resolve("logs"));
		return new ProductDataLayout(root, config, cache, instance, datasets, previews, jobs, logs);
	}

	public static Path defaultRoot() {
		String localAppData = System.getenv("LOCALAPPDATA");
		if (localAppData != null && !localAppData.isBlank()) return Path.of(localAppData, "Vector2World");
		return Path.of(System.getProperty("user.home"), "AppData", "Local", "Vector2World");
	}

	private static Properties migrateSettings(Path file) throws IOException {
		Properties properties = new Properties();
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, UTF_8)) {
				properties.load(reader);
			} catch (IllegalArgumentException exception) {
				backup(file, "invalid");
				properties.clear();
			}
		}
		int schema;
		try { schema = Integer.parseInt(properties.getProperty("schema.version", "0")); }
		catch (NumberFormatException exception) {
			backup(file, "invalid-schema");
			properties.clear();
			schema = 0;
		}
		if (schema > SETTINGS_SCHEMA_VERSION) {
			throw new IOException("Settings schema " + schema + " is newer than supported schema "
					+ SETTINGS_SCHEMA_VERSION + "; the original file was not modified");
		}
		if (schema < SETTINGS_SCHEMA_VERSION) {
			if (Files.exists(file)) backup(file, "schema-" + schema);
			properties.setProperty("schema.version", Integer.toString(SETTINGS_SCHEMA_VERSION));
			writeAtomically(file, properties);
		}
		return properties;
	}

	private static Path resolveDataRoot(Path productRoot, String configured) throws IOException {
		if (configured == null || configured.isBlank()) return productRoot.resolve("data").normalize();
		Path path = Path.of(configured).normalize();
		if (!path.isAbsolute()) throw new IOException("Configured data.root must be an absolute path");
		return path.toAbsolutePath().normalize();
	}

	private static String normalizeInstanceId(String value) throws IOException {
		String id = value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
		if (!id.matches("[A-Za-z0-9_-]{1,64}")) throw new IOException("Invalid instance identifier");
		return id;
	}

	private static void requireWritable(Path directory) throws IOException {
		if (!Files.isDirectory(directory) || !Files.isWritable(directory)) {
			throw new IOException("Product data directory is not writable: " + directory);
		}
		Path probe = Files.createTempFile(directory, ".write-probe-", ".tmp");
		Files.delete(probe);
	}

	private static void rejectUnc(Path path) throws IOException {
		if (path.toString().startsWith("\\\\")) throw new IOException("UNC product data paths are not supported");
	}

	private static void backup(Path file, String reason) throws IOException {
		if (!Files.exists(file)) return;
		String suffix = Long.toString(Instant.now().toEpochMilli());
		Files.copy(file, file.resolveSibling(file.getFileName() + ".backup-" + reason + "-" + suffix),
				StandardCopyOption.COPY_ATTRIBUTES);
	}

	private static void writeAtomically(Path file, Properties properties) throws IOException {
		Path temporary = Files.createTempFile(file.getParent(), "settings-", ".tmp");
		try {
			try (Writer writer = Files.newBufferedWriter(temporary, UTF_8, StandardOpenOption.TRUNCATE_EXISTING)) {
				properties.store(writer, "Vector2World settings schema");
			}
			try {
				Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException unsupportedAtomicMove) {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}
}
