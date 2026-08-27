package org.osm2world.buildingtiler.api;

import java.io.IOException;
import java.util.Locale;

import org.osm2world.buildingtiler.application.ModelPreviewService;
import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.domain.HeightUnit;
import org.osm2world.buildingtiler.domain.InvalidHeightPolicy;
import org.osm2world.buildingtiler.domain.ModelingConfig;
import org.osm2world.buildingtiler.domain.RoofMode;
import org.osm2world.buildingtiler.domain.RuleVersion;
import org.osm2world.buildingtiler.domain.StylePresetId;
import org.osm2world.buildingtiler.gis.DatasetErrorCode;
import org.osm2world.buildingtiler.gis.DatasetImportException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/model-previews")
public final class ModelPreviewController {

	private final ModelPreviewService previews;

	public ModelPreviewController(ModelPreviewService previews) {
		this.previews = previews;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ModelPreviewResponse> create(@RequestBody ModelPreviewRequest request)
			throws DatasetImportException {
		if (request == null || blank(request.datasetId()) || blank(request.heightField())) {
			throw new DatasetImportException(DatasetErrorCode.INVALID_REQUEST,
					"datasetId and heightField are required");
		}
		HeightMapping mapping = new HeightMapping(request.heightField(), parseUnit(request.heightUnit()),
				parsePolicy(request.invalidPolicy()), request.maximumHeightMeters() == null
						? HeightMapping.DEFAULT_MAXIMUM_HEIGHT_METERS : request.maximumHeightMeters());
		ModelingConfig config = config(request);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ModelPreviewResponse.from(previews.create(request.datasetId(), mapping, config)));
	}

	@GetMapping(value = "/{previewId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ModelPreviewResponse status(@PathVariable("previewId") String previewId) throws DatasetImportException {
		return ModelPreviewResponse.from(previews.get(previewId));
	}

	@GetMapping("/{previewId}/files/{fileName:.+}")
	public ResponseEntity<FileSystemResource> file(@PathVariable("previewId") String previewId,
			@PathVariable("fileName") String fileName) throws DatasetImportException {
		var file = previews.resultFile(previewId, fileName);
		MediaType type = fileName.endsWith(".json") ? MediaType.APPLICATION_JSON
				: MediaType.parseMediaType("model/gltf-binary");
		return ResponseEntity.ok().contentType(type).body(new FileSystemResource(file));
	}

	@DeleteMapping("/{previewId}")
	public ResponseEntity<Void> delete(@PathVariable("previewId") String previewId) throws IOException {
		previews.delete(previewId);
		return ResponseEntity.noContent().build();
	}

	private static ModelingConfig config(ModelPreviewRequest request) {
		ModelingConfig defaults = ModelingConfig.defaults();
		RuleVersion version = blank(request.ruleVersion()) ? defaults.ruleVersion() : new RuleVersion(request.ruleVersion());
		RoofMode roofMode = blank(request.roofMode()) ? defaults.roofMode()
				: RoofMode.valueOf(request.roofMode().trim().toUpperCase(Locale.ROOT).replace('-', '_'));
		StylePresetId preset = StylePresetId.parse(request.stylePreset());
		return new ModelingConfig(version, roofMode, preset,
				value(request.floorHeightMeters(), defaults.floorHeightMeters()),
				value(request.roofHeightRatio(), defaults.roofHeightRatio()),
				value(request.minimumRoofHeightMeters(), defaults.minimumRoofHeightMeters()),
				value(request.maximumRoofHeightMeters(), defaults.maximumRoofHeightMeters()),
				value(request.minimumBodyHeightMeters(), defaults.minimumBodyHeightMeters()),
				value(request.minimumPitchedBuildingHeightMeters(), defaults.minimumPitchedBuildingHeightMeters()),
				value(request.maximumPitchedBuildingHeightMeters(), defaults.maximumPitchedBuildingHeightMeters()),
				request.lod() == null ? defaults.lod() : request.lod(),
				request.sampleSize() == null ? defaults.previewSampleSize() : request.sampleSize(),
				request.variantSeed() == null ? defaults.variantSeed() : request.variantSeed(),
				defaults.footprintThresholds());
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

	private static boolean blank(String value) { return value == null || value.isBlank(); }
	private static double value(Double value, double defaultValue) { return value == null ? defaultValue : value; }
}
