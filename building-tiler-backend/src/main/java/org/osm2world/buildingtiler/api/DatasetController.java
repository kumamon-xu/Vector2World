package org.osm2world.buildingtiler.api;

import java.io.IOException;
import java.nio.charset.Charset;

import org.osm2world.buildingtiler.application.DatasetService;
import org.osm2world.buildingtiler.application.ManagedDataset;
import org.osm2world.buildingtiler.application.PreviewGeoJsonService;
import org.osm2world.buildingtiler.domain.DatasetMetadata;
import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.domain.HeightUnit;
import org.osm2world.buildingtiler.domain.InvalidHeightPolicy;
import org.osm2world.buildingtiler.gis.DatasetErrorCode;
import org.osm2world.buildingtiler.gis.DatasetImportException;
import org.osm2world.buildingtiler.gis.ImportOptions;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/datasets")
public final class DatasetController {

	private final DatasetService datasets;
	private final PreviewGeoJsonService previews;

	public DatasetController(DatasetService datasets, PreviewGeoJsonService previews) {
		this.datasets = datasets;
		this.previews = previews;
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<DatasetResponse> upload(
			@RequestParam("file") MultipartFile file,
			@RequestParam(name = "sourceCrs", required = false) String sourceCrs,
			@RequestParam(name = "layer", required = false) String layer,
			@RequestParam(name = "dbfCharset", required = false) String dbfCharset,
			@RequestParam(name = "heightField", required = false) String heightField,
			@RequestParam(name = "heightUnit", defaultValue = "m") String heightUnit,
			@RequestParam(name = "maximumHeightMeters", defaultValue = "10000") double maximumHeightMeters) throws IOException {
		Charset charset = parseCharset(dbfCharset);
		ImportOptions options = new ImportOptions(sourceCrs, layer, charset,
				ImportOptions.defaults().timeout(), ImportOptions.defaults().repairWarningAreaRatio(),
				ImportOptions.defaults().repairRejectAreaRatio());
		ManagedDataset dataset = datasets.upload(file.getOriginalFilename(), file.getContentType(),
				file.getSize(), file.getInputStream(), options);
		DatasetMetadata metadata = null;
		if (heightField != null && !heightField.isBlank()) {
			metadata = datasets.materialize(dataset.id().toString(),
					new HeightMapping(heightField, parseUnit(heightUnit), InvalidHeightPolicy.SKIP, maximumHeightMeters))
					.metadata();
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(DatasetResponse.from(dataset, metadata));
	}

	@GetMapping(value = "/{datasetId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public DatasetResponse metadata(@PathVariable("datasetId") String datasetId) throws DatasetImportException {
		ManagedDataset dataset = datasets.get(datasetId);
		DatasetMetadata materialized = dataset.heightMapping() == null ? null
				: dataset.inspection().materialize(dataset.heightMapping()).metadata();
		return DatasetResponse.from(dataset, materialized);
	}

	@PostMapping(value = "/{datasetId}/height-mapping", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public DatasetResponse mapHeight(@PathVariable("datasetId") String datasetId,
			@RequestBody HeightMappingRequest request) throws DatasetImportException {
		HeightMapping mapping = new HeightMapping(request.fieldName(), parseUnit(request.unit()),
				parsePolicy(request.invalidPolicy()), request.maximumHeightMeters() == null
						? HeightMapping.DEFAULT_MAXIMUM_HEIGHT_METERS : request.maximumHeightMeters());
		DatasetMetadata metadata = datasets.materialize(datasetId, mapping).metadata();
		return DatasetResponse.from(datasets.get(datasetId), metadata);
	}

	@GetMapping(value = "/{datasetId}/preview", produces = "application/geo+json")
	public ResponseEntity<String> preview(@PathVariable("datasetId") String datasetId) throws Exception {
		String json = previews.render(datasets.get(datasetId).inspection());
		return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/geo+json")).body(json);
	}

	@DeleteMapping("/{datasetId}")
	public ResponseEntity<Void> delete(@PathVariable("datasetId") String datasetId) throws IOException {
		datasets.delete(datasetId);
		return ResponseEntity.noContent().build();
	}

	private static Charset parseCharset(String value) throws DatasetImportException {
		if (value == null || value.isBlank()) return null;
		try { return Charset.forName(value); }
		catch (RuntimeException exception) {
			throw new DatasetImportException(DatasetErrorCode.INVALID_REQUEST,
					"Unsupported DBF charset: " + value, exception);
		}
	}

	private static HeightUnit parseUnit(String value) throws DatasetImportException {
		try { return HeightUnit.parse(value); }
		catch (RuntimeException exception) {
			throw new DatasetImportException(DatasetErrorCode.INVALID_REQUEST,
					"Height unit must be one of m, cm, mm or ft", exception);
		}
	}

	private static InvalidHeightPolicy parsePolicy(String value) throws DatasetImportException {
		if (value == null || value.isBlank()) return InvalidHeightPolicy.SKIP;
		try { return InvalidHeightPolicy.valueOf(value.trim().toUpperCase()); }
		catch (RuntimeException exception) {
			throw new DatasetImportException(DatasetErrorCode.INVALID_REQUEST,
					"Invalid height policy must be SKIP or FAIL", exception);
		}
	}
}
