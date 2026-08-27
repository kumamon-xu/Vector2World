package org.osm2world.buildingtiler.product;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import org.osm2world.buildingtiler.application.DatasetService;
import org.osm2world.buildingtiler.application.GenerationJobService;
import org.osm2world.buildingtiler.gis.DatasetErrorCode;
import org.osm2world.buildingtiler.gis.DatasetImportException;

public final class ManagedDirectoryService {

	@FunctionalInterface
	public interface DirectoryLauncher { void open(Path directory) throws IOException; }

	private final DatasetService datasets;
	private final GenerationJobService jobs;
	private final DirectoryLauncher launcher;

	public ManagedDirectoryService(DatasetService datasets, GenerationJobService jobs, DirectoryLauncher launcher) {
		this.datasets = datasets;
		this.jobs = jobs;
		this.launcher = launcher;
	}

	public static ManagedDirectoryService desktop(DatasetService datasets, GenerationJobService jobs) {
		return new ManagedDirectoryService(datasets, jobs, directory -> {
			if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
				throw new IOException("Desktop directory opening is unavailable");
			}
			Desktop.getDesktop().open(directory.toFile());
		});
	}

	public Path open(String type, String id) throws IOException {
		Path root;
		Path candidate;
		if ("dataset".equals(type)) {
			root = datasets.storageRoot();
			candidate = datasets.managedDirectory(id);
		} else if ("job".equals(type)) {
			root = jobs.storageRoot();
			candidate = jobs.managedDirectory(id);
		} else {
			throw new DatasetImportException(DatasetErrorCode.INVALID_REQUEST,
					"Directory type must be 'dataset' or 'job'");
		}
		Path safe = validateManagedPath(root, candidate);
		launcher.open(safe);
		return safe;
	}

	static Path validateManagedPath(Path configuredRoot, Path candidate) throws IOException {
		if (configuredRoot == null || candidate == null) throw new IOException("Managed directory is unavailable");
		if (configuredRoot.toString().startsWith("\\\\") || candidate.toString().startsWith("\\\\")) {
			throw new IOException("UNC paths are not allowed");
		}
		if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Managed directory was deleted");
		Path realRoot = configuredRoot.toRealPath();
		Path realCandidate = candidate.toRealPath();
		if (!Files.isDirectory(realCandidate) || !realCandidate.startsWith(realRoot)) {
			throw new IOException("Managed directory escaped its configured root");
		}
		return realCandidate;
	}
}
