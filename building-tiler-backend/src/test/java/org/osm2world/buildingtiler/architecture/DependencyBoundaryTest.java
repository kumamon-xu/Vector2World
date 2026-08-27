package org.osm2world.buildingtiler.architecture;

import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class DependencyBoundaryTest {

	@Test
	void applicationAndCliDoNotImportOsm2WorldEngineApis() throws Exception {
		Path sourceRoot = Path.of("").toAbsolutePath().resolve("src/main/java");
		if (!Files.isDirectory(sourceRoot)) {
			sourceRoot = Path.of("").toAbsolutePath().resolve("building-tiler-backend/src/main/java");
		}
		List<String> violations = new ArrayList<>();
		for (String packageName : List.of("application", "api", "cli", "modeling")) {
			Path packageDirectory = sourceRoot.resolve("org/osm2world/buildingtiler/" + packageName);
			try (var files = Files.walk(packageDirectory)) {
				for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
					for (String line : Files.readAllLines(file)) {
						String trimmed = line.trim();
						if (trimmed.startsWith("import org.osm2world.")
								&& !trimmed.startsWith("import org.osm2world.buildingtiler.")) {
							violations.add(file.getFileName() + ": " + trimmed);
						}
					}
				}
			}
		}
		if (!violations.isEmpty()) fail("OSM2World engine imports crossed the adapter boundary: " + violations);
	}

	@Test
	void domainDoesNotExposeGeoToolsOrOsm2WorldTypes() throws Exception {
		Path sourceRoot = Path.of("").toAbsolutePath().resolve("src/main/java");
		if (!Files.isDirectory(sourceRoot)) {
			sourceRoot = Path.of("").toAbsolutePath().resolve("building-tiler-backend/src/main/java");
		}
		Path domain = sourceRoot.resolve("org/osm2world/buildingtiler/domain");
		List<String> violations = new ArrayList<>();
		try (var files = Files.walk(domain)) {
			for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
				for (String line : Files.readAllLines(file)) {
					String trimmed = line.trim();
					if (trimmed.startsWith("import org.geotools.")
							|| (trimmed.startsWith("import org.osm2world.")
									&& !trimmed.startsWith("import org.osm2world.buildingtiler."))) {
						violations.add(file.getFileName() + ": " + trimmed);
					}
				}
			}
		}
		if (!violations.isEmpty()) fail("GIS/upstream library types leaked into domain: " + violations);
	}

}
