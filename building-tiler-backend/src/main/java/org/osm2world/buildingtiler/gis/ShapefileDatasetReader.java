package org.osm2world.buildingtiler.gis;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.geotools.api.data.Query;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.feature.type.AttributeDescriptor;
import org.geotools.api.feature.type.GeometryDescriptor;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.osm2world.buildingtiler.domain.BuildingPartId;

public final class ShapefileDatasetReader implements InspectingDatasetReader {

	@Override
	public DatasetInspection inspect(Path input, ImportOptions options) throws IOException {
		return inspect(input, options, options.newDeadline());
	}

	public DatasetInspection inspect(Path input, ImportOptions options, ImportDeadline deadline) throws IOException {
		try (var features = NormalizedFeatureStore.memory()) {
			return inspect(input, options, deadline, features);
		}
	}

	public DatasetInspection inspectToStore(Path input, ImportOptions options, ImportDeadline deadline,
			Path store, long maximumStoreBytes) throws IOException {
		try (var features = NormalizedFeatureStore.streaming(store, maximumStoreBytes, deadline)) {
			return inspect(input, options, deadline, features);
		}
	}

	private DatasetInspection inspect(Path input, ImportOptions options, ImportDeadline deadline,
			NormalizedFeatureStore.FeatureSink accepted) throws IOException {
		deadline.check("shapefile initialization");
		if (!Files.isRegularFile(input)) throw new DatasetImportException(DatasetErrorCode.UNSUPPORTED_FORMAT,
				"Shapefile does not exist");
		for (String extension : List.of(".shx", ".dbf")) {
			if (findSidecar(input, extension) == null) {
				throw new DatasetImportException(DatasetErrorCode.SHAPEFILE_SIDECAR_MISSING,
						"Shapefile requires matching " + extension + " sidecar",
						Map.of("missingExtension", extension));
			}
		}
		Path prj = findSidecar(input, ".prj");
		if (prj == null && (options.explicitCrs() == null || options.explicitCrs().isBlank())) {
			throw new DatasetImportException(DatasetErrorCode.CRS_REQUIRED,
					"Shapefile .prj is missing; provide an explicit source CRS");
		}

		Charset charset = options.dbfCharset() != null ? options.dbfCharset() : readCpg(input);
		if (charset == null) charset = StandardCharsets.ISO_8859_1;
		deadline.check("shapefile datastore initialization");
		ShapefileDataStore dataStore = new ShapefileDataStore(input.toUri().toURL());
		dataStore.setCharset(charset);
		try {
			String[] names = dataStore.getTypeNames();
			if (names.length == 0) throw new DatasetImportException(DatasetErrorCode.LAYER_NOT_FOUND,
					"Shapefile contains no readable layer");
			String typeName = selectLayer(names, options.selectedLayer());
			SimpleFeatureType schema = dataStore.getSchema(typeName);
			CrsSupport.ResolvedCrs crs = CrsSupport.resolve(schema.getCoordinateReferenceSystem(), options.explicitCrs());
			List<AttributeDescriptor> attributes = schema.getAttributeDescriptors().stream()
					.filter(descriptor -> !(descriptor instanceof GeometryDescriptor)).toList();
			FieldProfiler fields = new FieldProfiler();
			ImportIssueCollector issues = new ImportIssueCollector();
			Map<String, Long> geometryTypes = new LinkedHashMap<>();
			Map<String, Integer> ids = new HashMap<>();
			Envelope bounds = new Envelope();
			long featureCount = 0;
			long skipped = 0;
			long repaired = 0;

			var collection = dataStore.getFeatureSource(typeName).getFeatures(Query.ALL);
			try (var iterator = collection.features()) {
				while (iterator.hasNext()) {
					deadline.check("shapefile feature read");
					SimpleFeature feature = iterator.next();
					featureCount++;
					Map<String, Object> properties = new LinkedHashMap<>();
					for (AttributeDescriptor descriptor : attributes) {
						properties.put(descriptor.getLocalName(), feature.getAttribute(descriptor.getLocalName()));
					}
					fields.accept(properties);
					Object value = feature.getDefaultGeometry();
					String geometryType = value instanceof Geometry geometry ? geometry.getGeometryType() : "null";
					geometryTypes.merge(geometryType, 1L, Long::sum);
					if (!(value instanceof Geometry geometry)) {
						skipped++;
						issues.error("UNSUPPORTED_OR_MISSING_GEOMETRY", "Feature has no polygonal geometry");
						continue;
					}
					Geometry wgs84 = CrsSupport.toWgs84(geometry, crs);
					deadline.check("shapefile CRS transform");
					GeometryNormalizer.Result normalized = GeometryNormalizer.normalize(wgs84, options);
					deadline.check("shapefile geometry repair");
					if (!normalized.accepted()) {
						skipped++;
						issues.error(normalized.rejectionCode(), normalized.rejectionMessage());
						continue;
					}
					if (normalized.repaired()) repaired++;
					if (normalized.warning() != null) issues.warning("REPAIR_AREA_CHANGED", normalized.warning());
					String baseId = StableIdGenerator.baseId(feature.getID(), normalized.geometry(), properties);
					deadline.check("shapefile stable ID hashing");
					int occurrence = ids.merge(baseId, 1, Integer::sum);
					String id = occurrence == 1 ? baseId
							: baseId + "~" + StableIdGenerator.collisionSuffix(baseId, normalized.geometry(), properties)
									+ "-" + occurrence;
					if (occurrence > 1) issues.warning("DUPLICATE_FEATURE_ID", "Duplicate source feature ID was disambiguated");
					int partCount = normalized.geometry() instanceof MultiPolygon multi ? multi.getNumGeometries() : 1;
					List<BuildingPartId> partIds = new ArrayList<>(partCount);
					for (int part = 0; part < partCount; part++) partIds.add(new BuildingPartId(id, part));
					accepted.add(new SourceBuildingFeature(id, normalized.geometry(), properties, partIds,
							geometryType, normalized.repaired()));
					ImportMemoryGuard.check(accepted.size(), "shapefile normalized feature retention");
					bounds.expandToInclude(normalized.geometry().getEnvelopeInternal());
				}
			}
			deadline.check("shapefile metadata finalization");
			List<SourceBuildingFeature> features = accepted.finish();

			List<LayerMetadata> layers = new ArrayList<>();
			for (String name : names) layers.add(new LayerMetadata(name,
					name.equals(typeName) ? dominantType(geometryTypes) : "Unknown", name.equals(typeName)));
			return new DatasetInspection(input.toAbsolutePath(), "SHP", crs.name(), crs.sourceKind().name(),
					charset.name(), null, false,
					layers, featureCount, features, fields.metadata(), fields.heightCandidates(),
					geometryTypes, bounds, skipped, repaired, issues.result());
		} catch (DatasetImportException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new DatasetImportException(DatasetErrorCode.UNSUPPORTED_FORMAT,
					"Failed to read shapefile: " + exception.getMessage(), exception);
		} finally {
			dataStore.dispose();
		}
	}

	private static String selectLayer(String[] names, String selected) throws DatasetImportException {
		if (selected == null || selected.isBlank()) {
			if (names.length == 1) return names[0];
			throw new DatasetImportException(DatasetErrorCode.LAYER_SELECTION_REQUIRED,
					"Input contains multiple layers; select one explicitly", Map.of("layers", List.of(names)));
		}
		for (String name : names) if (name.equals(selected)) return name;
		throw new DatasetImportException(DatasetErrorCode.LAYER_NOT_FOUND,
				"Selected layer does not exist: " + selected, Map.of("layers", List.of(names)));
	}

	private static Charset readCpg(Path shapefile) throws IOException {
		Path cpg = findSidecar(shapefile, ".cpg");
		if (cpg == null) return null;
		String name = Files.readString(cpg).trim().replace("\"", "");
		if (name.isBlank()) return null;
		if (name.equals("936")) name = "GBK";
		if (name.equals("65001")) name = "UTF-8";
		try { return Charset.forName(name); }
		catch (RuntimeException exception) {
			throw new DatasetImportException(DatasetErrorCode.UNSUPPORTED_FORMAT,
					"Unsupported DBF charset in .cpg: " + name, exception);
		}
	}

	static Path findSidecar(Path shapefile, String extension) throws IOException {
		String fileName = shapefile.getFileName().toString();
		int dot = fileName.lastIndexOf('.');
		String base = dot < 0 ? fileName : fileName.substring(0, dot);
		try (var siblings = Files.list(shapefile.toAbsolutePath().getParent())) {
			return siblings.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
							.equals((base + extension).toLowerCase(Locale.ROOT)))
					.findFirst().orElse(null);
		}
	}

	private static String dominantType(Map<String, Long> types) {
		return types.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("Unknown");
	}
}
