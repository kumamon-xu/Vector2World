package org.osm2world.buildingtiler.gis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.geotools.api.data.DataStore;
import org.geotools.api.data.DataStoreFinder;
import org.geotools.api.data.Query;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.osm2world.buildingtiler.domain.BuildingFeature;
import org.osm2world.buildingtiler.domain.DatasetMetadata;

public final class ShapefileBuildingReader implements DatasetReader {

	@Override
	public DatasetReadResult read(Path input, String heightField) throws IOException {
		if (!Files.isRegularFile(input)) throw new IOException("Shapefile does not exist: " + input);
		Path prj = replaceExtension(input, ".prj");
		if (!Files.isRegularFile(prj)) throw new IOException("Shapefile .prj is required for M0: " + prj);

		DataStore dataStore = DataStoreFinder.getDataStore(Map.of("url", input.toUri().toURL()));
		if (dataStore == null) throw new IOException("GeoTools could not open shapefile: " + input);

		try {
			String typeName = dataStore.getTypeNames()[0];
			SimpleFeatureType schema = dataStore.getSchema(typeName);
			CoordinateReferenceSystem sourceCrs = schema.getCoordinateReferenceSystem();
			if (sourceCrs == null) throw new IOException("Shapefile CRS could not be resolved from " + prj);
			CoordinateReferenceSystem targetCrs = CRS.decode("EPSG:4326", true);
			MathTransform transform = CRS.findMathTransform(sourceCrs, targetCrs, true);

			List<BuildingFeature> buildings = new ArrayList<>();
			Map<String, Long> geometryTypes = new LinkedHashMap<>();
			Envelope bounds = new Envelope();
			long featureCount = 0;
			long skippedHeight = 0;
			long skippedGeometry = 0;

			SimpleFeatureCollection collection = dataStore.getFeatureSource(typeName).getFeatures(Query.ALL);
			try (var features = collection.features()) {
				while (features.hasNext()) {
					SimpleFeature feature = features.next();
					featureCount++;
					Double height = GeometrySupport.parseHeight(feature.getAttribute(heightField));
					if (height == null) {
						skippedHeight++;
						continue;
					}

					Object rawGeometry = feature.getDefaultGeometry();
					if (!(rawGeometry instanceof Geometry geometry)) {
						skippedGeometry++;
						continue;
					}
					geometryTypes.merge(geometry.getGeometryType(), 1L, Long::sum);
					Geometry wgs84 = GeometrySupport.normalizePolygonal(JTS.transform(geometry, transform));
					if (wgs84 == null) {
						skippedGeometry++;
						continue;
					}

					buildings.add(new BuildingFeature(feature.getID(), wgs84, height,
							Map.of("sourceIndex", featureCount, "heightField", heightField)));
					bounds.expandToInclude(wgs84.getEnvelopeInternal());
				}
			}

			if (buildings.isEmpty()) throw new IOException("No valid buildings found in " + input);
			double minHeight = buildings.stream().mapToDouble(BuildingFeature::heightMeters).min().orElseThrow();
			double maxHeight = buildings.stream().mapToDouble(BuildingFeature::heightMeters).max().orElseThrow();
			String sourceCrsName = CRS.toSRS(sourceCrs, true);
			if (sourceCrsName == null) sourceCrsName = sourceCrs.getName().toString();

			DatasetMetadata metadata = new DatasetMetadata(input.toAbsolutePath(), "SHP", sourceCrsName,
					"DBF charset unspecified",
					featureCount, buildings.size(), skippedHeight, skippedGeometry, bounds,
					Map.copyOf(geometryTypes), minHeight, maxHeight);
			return new DatasetReadResult(buildings, metadata);
		} catch (Exception e) {
			if (e instanceof IOException ioException) throw ioException;
			throw new IOException("Failed to read shapefile " + input + ": " + e.getMessage(), e);
		} finally {
			dataStore.dispose();
		}
	}

	private static Path replaceExtension(Path input, String extension) {
		String name = input.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String base = dot >= 0 ? name.substring(0, dot) : name;
		return input.resolveSibling(base + extension);
	}

}
