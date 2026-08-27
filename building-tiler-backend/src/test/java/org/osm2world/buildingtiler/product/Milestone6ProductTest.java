package org.osm2world.buildingtiler.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osm2world.buildingtiler.api.ApiExceptionHandler;
import org.osm2world.buildingtiler.api.SystemController;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class Milestone6ProductTest {

	@TempDir Path temporary;

	@Test
	void createsVersionedUnicodeSafeIsolatedProductData() throws Exception {
		Path root = temporary.resolve("带 空格的产品数据");
		ProductDataLayout first = ProductDataLayout.open(root, "instance-a");
		ProductDataLayout second = ProductDataLayout.open(root, "instance-b");

		assertThat(first.root()).isEqualTo(root.toAbsolutePath());
		assertThat(first.datasets()).isDirectory();
		assertThat(first.previews()).isDirectory();
		assertThat(first.jobs()).isDirectory();
		assertThat(first.logs()).isDirectory();
		assertThat(first.instance()).isNotEqualTo(second.instance());
		assertThat(Files.readString(root.resolve("config/settings.properties")))
				.contains("schema.version=1");
	}

	@Test
	void backsUpInvalidSettingsAndRecoversDefaults() throws Exception {
		Path config = Files.createDirectories(temporary.resolve("recover/config"));
		Path settings = config.resolve("settings.properties");
		Files.writeString(settings, "schema.version=broken\n");

		ProductDataLayout layout = ProductDataLayout.open(temporary.resolve("recover"), "restored");

		assertThat(layout.jobs()).isDirectory();
		try (var files = Files.list(config)) {
			assertThat(files.map(path -> path.getFileName().toString()).toList())
					.anyMatch(name -> name.startsWith("settings.properties.backup-invalid-schema-"));
		}
	}

	@Test
	void refusesFutureSettingsWithoutOverwritingThem() throws Exception {
		Path config = Files.createDirectories(temporary.resolve("future/config"));
		Path settings = config.resolve("settings.properties");
		String future = "schema.version=999\ndata.root=C:\\\\keep-me\n";
		Files.writeString(settings, future);

		assertThatThrownBy(() -> ProductDataLayout.open(temporary.resolve("future"), "future"))
				.isInstanceOf(IOException.class).hasMessageContaining("newer than supported");
		assertThat(Files.readString(settings)).isEqualTo(future);
	}

	@Test
	void validatesManagedDirectoriesAndRejectsEscapeOrDeletedTargets() throws Exception {
		Path root = Files.createDirectories(temporary.resolve("managed"));
		Path inside = Files.createDirectories(root.resolve("任务 成果"));
		Path outside = Files.createDirectories(temporary.resolve("outside"));

		assertThat(ManagedDirectoryService.validateManagedPath(root, inside)).isEqualTo(inside.toRealPath());
		assertThatThrownBy(() -> ManagedDirectoryService.validateManagedPath(root, outside))
				.isInstanceOf(IOException.class).hasMessageContaining("escaped");
		Files.delete(inside);
		assertThatThrownBy(() -> ManagedDirectoryService.validateManagedPath(root, inside))
				.isInstanceOf(IOException.class).hasMessageContaining("deleted");
	}

	@Test
	void rejectsSymlinkEscapeWhenTheHostCanCreateDirectorySymlinks() throws Exception {
		Path root = Files.createDirectories(temporary.resolve("links"));
		Path outside = Files.createDirectories(temporary.resolve("link-outside"));
		Path link = root.resolve("escaped-link");
		boolean created;
		try { Files.createSymbolicLink(link, outside); created = true; }
		catch (UnsupportedOperationException | IOException | SecurityException exception) { created = false; }
		assumeTrue(created, "Host does not permit unprivileged directory symlinks");
		assertThatThrownBy(() -> ManagedDirectoryService.validateManagedPath(root, link))
				.isInstanceOf(IOException.class).hasMessageContaining("escaped");
	}

	@Test
	void exposesHealthAndTraceableBuildMetadata() throws Exception {
		ManagedDirectoryService directories = mock(ManagedDirectoryService.class);
		MockMvc mvc = MockMvcBuilders.standaloneSetup(new SystemController(directories))
				.setControllerAdvice(new ApiExceptionHandler()).build();

		mvc.perform(get("/api/system/health"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
		mvc.perform(get("/api/system/about"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Vector2World"))
				.andExpect(jsonPath("$.version").isNotEmpty())
				.andExpect(jsonPath("$.osm2worldCommit").value("bfa31df1124295721ec848273fbf93ab46b24d25"));

		mvc.perform(post("/api/system/open-directory").contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"job\",\"id\":\"00000000-0000-0000-0000-000000000001\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.opened").value(true));
		verify(directories).open("job", "00000000-0000-0000-0000-000000000001");

		mvc.perform(post("/api/system/open-directory").contentType(MediaType.APPLICATION_JSON)
				.content("{\"path\":\"C:\\\\Windows\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void buildMetadataHasEveryReleaseTraceabilityField() {
		ProductBuildInfo info = ProductBuildInfo.current();
		assertThat(List.of(info.version(), info.buildNumber(), info.gitSha(), info.buildTime(),
				info.osm2worldCommit(), info.ruleVersion(), info.presetVersion()))
				.allMatch(value -> value != null && !value.isBlank());
		assertThat(info.asMap()).containsKeys("gitDirty", "packaged");
	}
}
