package org.osm2world.buildingtiler.api;

import java.io.IOException;
	import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.osm2world.buildingtiler.application.GenerationJobEvent;
import org.osm2world.buildingtiler.application.GenerationJobService;
import org.osm2world.buildingtiler.application.GenerationJobSpec;
import org.osm2world.buildingtiler.application.ManagedGenerationJob;
import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.domain.HeightUnit;
import org.osm2world.buildingtiler.domain.InvalidHeightPolicy;
import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.domain.OutputFormat;
import org.osm2world.buildingtiler.domain.RoofMode;
import org.osm2world.buildingtiler.domain.RuleVersion;
import org.osm2world.buildingtiler.domain.StylePresetId;
import org.osm2world.buildingtiler.domain.TilingConfig;
import org.osm2world.buildingtiler.gis.DatasetErrorCode;
import org.osm2world.buildingtiler.gis.DatasetImportException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/jobs")
public final class GenerationJobController {

	private final GenerationJobService jobs;

	public GenerationJobController(GenerationJobService jobs) {
		this.jobs = jobs;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<GenerationJobResponse> create(@RequestBody CreateGenerationJobRequest request)
			throws IOException {
		if (request == null || blank(request.datasetId()) || blank(request.heightField())) {
			throw new DatasetImportException(DatasetErrorCode.INVALID_REQUEST,
					"datasetId and heightField are required");
		}
		ManagedGenerationJob job = jobs.create(new GenerationJobSpec(request.datasetId(), heightMapping(request),
				modelingConfig(request), tilingConfig(request)));
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(GenerationJobResponse.from(job));
	}

	@GetMapping(value = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public GenerationJobResponse status(@PathVariable("jobId") String jobId) throws DatasetImportException {
		return GenerationJobResponse.from(jobs.get(jobId));
	}

	@GetMapping(value = "/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter events(@PathVariable("jobId") String jobId,
			@RequestHeader(name = "Last-Event-ID", required = false) String lastEventId)
			throws DatasetImportException {
		ManagedGenerationJob job = jobs.get(jobId);
		long after = parseEventId(lastEventId);
		SseEmitter emitter = new SseEmitter(0L);
		@SuppressWarnings("unchecked")
		Consumer<GenerationJobEvent>[] holder = new Consumer[1];
		holder[0] = event -> {
			try {
				emitter.send(SseEmitter.event().id(Long.toString(event.id())).name("job").data(event));
				if (event.state().terminal()) {
					job.unsubscribe(holder[0]);
					emitter.complete();
				}
			} catch (IOException exception) {
				job.unsubscribe(holder[0]);
				// A browser refresh, navigation or EventSource close is a normal SSE
				// disconnect. Completing quietly avoids routing an already committed
				// text/event-stream response through the JSON exception handler.
				emitter.complete();
			}
		};
		emitter.onCompletion(() -> job.unsubscribe(holder[0]));
		emitter.onTimeout(() -> job.unsubscribe(holder[0]));
		job.replayAndSubscribe(after, holder[0]);
		if (job.state().terminal()) emitter.complete();
		return emitter;
	}

	@GetMapping("/{jobId}/tileset")
	public ResponseEntity<FileSystemResource> tileset(@PathVariable("jobId") String jobId)
			throws DatasetImportException {
		return jsonFile(jobId, "tileset.json");
	}

	@GetMapping("/{jobId}/files/{*filePath}")
	public ResponseEntity<FileSystemResource> asset(@PathVariable("jobId") String jobId,
			@PathVariable("filePath") String filePath) throws DatasetImportException {
		Path asset = jobs.resultAsset(jobId, filePath);
		MediaType type = asset.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".glb")
				? MediaType.parseMediaType("model/gltf-binary") : MediaType.APPLICATION_JSON;
		return ResponseEntity.ok().contentType(type).body(new FileSystemResource(asset));
	}

	@GetMapping("/{jobId}/manifest")
	public ResponseEntity<FileSystemResource> manifest(@PathVariable("jobId") String jobId)
			throws DatasetImportException {
		return jsonFile(jobId, "manifest.json");
	}

	@GetMapping("/{jobId}/report")
	public ResponseEntity<FileSystemResource> report(@PathVariable("jobId") String jobId)
			throws DatasetImportException {
		return jsonFile(jobId, "generation-report.json");
	}

	@GetMapping(value = "/{jobId}/download", produces = "application/zip")
	public ResponseEntity<StreamingResponseBody> download(@PathVariable("jobId") String jobId)
			throws DatasetImportException {
		jobs.resultFile(jobId, "tileset.json");
		StreamingResponseBody body = output -> jobs.streamZip(jobId, output);
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=vector2world-" + jobId + ".zip")
				.contentType(MediaType.parseMediaType("application/zip"))
				.body(body);
	}

	@GetMapping(value = "/{jobId}/diagnostics", produces = "application/zip")
	public ResponseEntity<StreamingResponseBody> diagnostics(@PathVariable("jobId") String jobId)
			throws DatasetImportException {
		jobs.get(jobId);
		StreamingResponseBody body = output -> jobs.streamDiagnosticsZip(jobId, output);
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION,
						"attachment; filename=vector2world-diagnostics-" + jobId + ".zip")
				.contentType(MediaType.parseMediaType("application/zip"))
				.body(body);
	}

	@DeleteMapping(value = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<GenerationJobResponse> cancel(@PathVariable("jobId") String jobId)
			throws DatasetImportException {
		return ResponseEntity.accepted().body(GenerationJobResponse.from(jobs.cancel(jobId)));
	}

	@PostMapping(value = "/{jobId}/retry-failed", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<GenerationJobResponse> retryFailed(@PathVariable("jobId") String jobId)
			throws IOException {
		return ResponseEntity.status(HttpStatus.ACCEPTED)
				.body(GenerationJobResponse.from(jobs.retryFailed(jobId)));
	}

	private ResponseEntity<FileSystemResource> jsonFile(String jobId, String fileName)
			throws DatasetImportException {
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
				.body(new FileSystemResource(jobs.resultFile(jobId, fileName)));
	}

	private HeightMapping heightMapping(CreateGenerationJobRequest request) throws DatasetImportException {
		return new HeightMapping(request.heightField(), parseUnit(request.heightUnit()),
				parsePolicy(request.invalidPolicy()), request.maximumHeightMeters() == null
						? HeightMapping.DEFAULT_MAXIMUM_HEIGHT_METERS : request.maximumHeightMeters());
	}

	private ModelingConfig modelingConfig(CreateGenerationJobRequest request) {
		ModelingConfig defaults = ModelingConfig.defaults();
		RuleVersion version = blank(request.ruleVersion()) ? defaults.ruleVersion() : new RuleVersion(request.ruleVersion());
		RoofMode roofMode = blank(request.roofMode()) ? defaults.roofMode()
				: RoofMode.valueOf(request.roofMode().trim().toUpperCase(Locale.ROOT).replace('-', '_'));
		return new ModelingConfig(version, roofMode, StylePresetId.parse(request.stylePreset()),
				value(request.floorHeightMeters(), defaults.floorHeightMeters()),
				value(request.roofHeightRatio(), defaults.roofHeightRatio()),
				value(request.minimumRoofHeightMeters(), defaults.minimumRoofHeightMeters()),
				value(request.maximumRoofHeightMeters(), defaults.maximumRoofHeightMeters()),
				value(request.minimumBodyHeightMeters(), defaults.minimumBodyHeightMeters()),
				value(request.minimumPitchedBuildingHeightMeters(), defaults.minimumPitchedBuildingHeightMeters()),
				value(request.maximumPitchedBuildingHeightMeters(), defaults.maximumPitchedBuildingHeightMeters()),
				2, 100, request.variantSeed() == null ? defaults.variantSeed() : request.variantSeed(),
				defaults.footprintThresholds());
	}

	private TilingConfig tilingConfig(CreateGenerationJobRequest request) {
		int workers = request.workerCount() == null ? jobs.recommendedWorkerCount() : request.workerCount();
		int queue = request.queueCapacity() == null ? jobs.queueCapacity() : request.queueCapacity();
		List<OutputFormat> formats = request.outputFormats() == null ? List.of(OutputFormat.THREE_D_TILES)
				: request.outputFormats().stream().map(OutputFormat::parse).toList();
		return new TilingConfig(request.zoom() == null ? TilingConfig.DEFAULT_ZOOM : request.zoom(),
				request.lods(), workers, queue,
				request.transientRetryCount() == null ? 1 : request.transientRetryCount(),
				request.crossTileBufferMeters() == null ? 0 : request.crossTileBufferMeters(),
				request.largeBuildingTileSpanWarning() == null ? 4 : request.largeBuildingTileSpanWarning(), formats);
	}

	private static HeightUnit parseUnit(String value) throws DatasetImportException {
		try { return HeightUnit.parse(blank(value) ? "m" : value); }
		catch (RuntimeException exception) {
			throw new DatasetImportException(DatasetErrorCode.INVALID_REQUEST,
					"Height unit must be one of m, cm, mm or ft", exception);
		}
	}

	private static InvalidHeightPolicy parsePolicy(String value) throws DatasetImportException {
		if (blank(value)) return InvalidHeightPolicy.SKIP;
		try { return InvalidHeightPolicy.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
		catch (RuntimeException exception) {
			throw new DatasetImportException(DatasetErrorCode.INVALID_REQUEST,
					"Invalid height policy must be SKIP or FAIL", exception);
		}
	}

	private static long parseEventId(String value) {
		if (blank(value)) return 0;
		try { return Math.max(0, Long.parseLong(value)); }
		catch (NumberFormatException exception) { throw new IllegalArgumentException("Last-Event-ID must be an integer"); }
	}

	private static boolean blank(String value) { return value == null || value.isBlank(); }
	private static double value(Double value, double fallback) { return value == null ? fallback : value; }
}
