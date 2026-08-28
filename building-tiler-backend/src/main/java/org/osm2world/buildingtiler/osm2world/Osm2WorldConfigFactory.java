package org.osm2world.buildingtiler.osm2world;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.conversion.O2WConfig;
import org.osm2world.util.platform.image.ImageImplementationJvm;
import org.osm2world.util.platform.json.JsonImplementationJvm;
import org.osm2world.util.platform.uri.HttpUriImplementationJvm;

/** Creates all production OSM2World configurations from one validated, versioned style bundle. */
public final class Osm2WorldConfigFactory {

	public static final String BUNDLE_VERSION = "vector2world-style-v1";
	private static final String RESOURCE_ROOT = "/osm2world-style/v1/";
	private static final List<String> BUNDLE_FILES = List.of(
			"style-bundle.properties", "LICENSE.txt",
			"textures/concrete.svg", "textures/stone.svg", "textures/brick.svg",
			"textures/wood.svg", "textures/glass.svg", "textures/metal.svg",
			"textures/tiles.svg", "textures/windows.svg");

	private static final Bundle DEFAULT_BUNDLE = materializeBundle();
	private static final Object PLATFORM_INITIALIZATION_LOCK = new Object();
	private static volatile boolean platformInitialized;
	private final Bundle bundle;

	public Osm2WorldConfigFactory() {
		this.bundle = DEFAULT_BUNDLE;
	}

	public O2WConfig create(ModelingConfig config, boolean clipToBounds) {
		ensureJvmPlatformInitialized();
		Map<String, Object> overrides = new LinkedHashMap<>();
		overrides.put("lod", config.lod());
		overrides.put("keepOsmElements", true);
		// O2WConfig list values use semicolons; a comma silently falls back to an empty enum set.
		overrides.put("exportMetadata", "ID;TAGS");
		overrides.put("clipToBounds", clipToBounds);
		overrides.put("gltfExtensionWhitelist", "KHR_mesh_quantization");
		overrides.put("useBuildingColors", true);
		overrides.put("renderUnderground", false);
		// ConversionLog still retains diagnostics for programmatic inspection; only suppress warning/error
		// stack traces on the process console so one imperfect source edge cannot flood worker logs.
		overrides.put("consoleLogLevels", "FATAL");
		overrides.put("explicitWindowImplementation", config.lod() >= 3 ? "FULL_GEOMETRY" : "FLAT_TEXTURES");
		overrides.put("implicitWindowImplementation", config.lod() >= 4 ? "FULL_GEOMETRY" : "FLAT_TEXTURES");
		overrides.put("staticResourceOutputMode", "EMBED");
		overrides.put("generatedResourceOutputMode", "EMBED");
		overrides.put("textureQuality", 0.9);
		overrides.put("maxTextureResolution", config.lod() >= 4 ? 1024 : 512);
		return new O2WConfig(overrides, bundle.configuration().toUri());
	}

	public BundleInfo bundleInfo() {
		return new BundleInfo(BUNDLE_VERSION, bundle.sha256(), bundle.directory().toString());
	}

	public static BundleInfo currentBundleInfo() {
		return new BundleInfo(BUNDLE_VERSION, DEFAULT_BUNDLE.sha256(), DEFAULT_BUNDLE.directory().toString());
	}

	private static void ensureJvmPlatformInitialized() {
		if (platformInitialized) return;
		synchronized (PLATFORM_INITIALIZATION_LOCK) {
			if (platformInitialized) return;
			HttpUriImplementationJvm.register();
			JsonImplementationJvm.register();
			ImageImplementationJvm.register();
			platformInitialized = true;
		}
	}

	private static Bundle materializeBundle() {
		try {
			Path root = Path.of(System.getProperty("java.io.tmpdir"), "vector2world-style", BUNDLE_VERSION)
					.toAbsolutePath().normalize();
			Files.createDirectories(root);
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String relative : BUNDLE_FILES) {
				Path target = root.resolve(relative).normalize();
				if (!target.startsWith(root)) throw new IllegalStateException("Style resource escaped bundle root");
				byte[] bytes;
				try (InputStream input = Osm2WorldConfigFactory.class.getResourceAsStream(RESOURCE_ROOT + relative)) {
					if (input == null) throw new IllegalStateException("Missing required OSM2World style resource: " + relative);
					bytes = input.readAllBytes();
				}
				if (bytes.length == 0) throw new IllegalStateException("Empty OSM2World style resource: " + relative);
				digest.update(relative.getBytes(java.nio.charset.StandardCharsets.UTF_8));
				digest.update(bytes);
				Files.createDirectories(target.getParent());
				Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
				Files.write(temporary, bytes);
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
			}
			Path configuration = root.resolve("style-bundle.properties");
			return new Bundle(root, configuration, HexFormat.of().formatHex(digest.digest()));
		} catch (IOException | NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Cannot materialize the required OSM2World style bundle", exception);
		}
	}

	private record Bundle(Path directory, Path configuration, String sha256) {}
	public record BundleInfo(String version, String sha256, String materializedDirectory) {}
}
