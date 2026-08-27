package org.osm2world.buildingtiler.application;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import com.google.gson.Gson;

final class CappedJsonLog {

	private static final Gson GSON = new Gson();
	private final Path file;
	private final long maximumBytes;

	CappedJsonLog(Path file, long maximumBytes) {
		this.file = file;
		this.maximumBytes = maximumBytes;
	}

	synchronized void append(Map<String, ?> value) {
		try {
			byte[] line = (GSON.toJson(value) + System.lineSeparator()).getBytes(UTF_8);
			if (Files.exists(file) && Files.size(file) + line.length > maximumBytes) {
				Path previous = file.resolveSibling(file.getFileName() + ".1");
				Files.move(file, previous, StandardCopyOption.REPLACE_EXISTING);
			}
			Files.write(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException ignored) {
			// Diagnostics must never change a generation result.
		}
	}
}
