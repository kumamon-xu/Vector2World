package org.osm2world.buildingtiler.api;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.osm2world.buildingtiler.product.ManagedDirectoryService;
import org.osm2world.buildingtiler.product.ProductBuildInfo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public final class SystemController {

	private final ManagedDirectoryService directories;

	public SystemController(ManagedDirectoryService directories) { this.directories = directories; }

	@GetMapping("/health")
	public Map<String, Object> health() {
		return Map.of("status", "UP", "timestamp", Instant.now().toString(),
				"pid", ManagementFactory.getRuntimeMXBean().getPid());
	}

	@GetMapping("/about")
	public Map<String, Object> about() { return ProductBuildInfo.current().asMap(); }

	@PostMapping("/open-directory")
	public ResponseEntity<Map<String, Object>> openDirectory(@RequestBody OpenDirectoryRequest request)
			throws IOException {
		if (request == null || request.type() == null || request.id() == null) {
			throw new IllegalArgumentException("type and id are required");
		}
		directories.open(request.type(), request.id());
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("opened", true);
		response.put("type", request.type());
		response.put("id", request.id());
		return ResponseEntity.ok(Map.copyOf(response));
	}

	public record OpenDirectoryRequest(String type, String id) { }
}
