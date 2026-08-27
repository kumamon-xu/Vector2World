package org.osm2world.buildingtiler.gis;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.osm2world.buildingtiler.domain.HeightMapping;
import org.osm2world.buildingtiler.domain.HeightUnit;

class GeoJsonDatasetReaderTest {

	@TempDir Path temporaryDirectory;
	private final GeoJsonDatasetReader reader = new GeoJsonDatasetReader();

	@Test
	void inspectsFieldsGeometryQualityAndMaterializesExplicitHeightMapping() throws Exception {
		Path input = Path.of(getClass().getResource("/m0-polygons.geojson").toURI());
		DatasetInspection inspection = reader.inspect(input, ImportOptions.defaults());

		assertEquals(4, inspection.featureCount());
		assertEquals(4, inspection.features().size());
		assertEquals("UTF-8", inspection.sourceEncoding());
		assertEquals("Elevation", inspection.heightCandidates().get(0).fieldName());
		assertEquals(3, inspection.heightCandidates().get(0).qualityAssumingMeters().valid());
		assertTrue(inspection.fields().stream().anyMatch(field -> field.name().equals("Elevation")));
		assertInstanceOf(Polygon.class, inspection.features().get(0).geometryWgs84());
		assertEquals(1, ((Polygon) inspection.features().get(0).geometryWgs84()).getNumInteriorRing());
		assertInstanceOf(MultiPolygon.class, inspection.features().get(1).geometryWgs84());
		assertEquals(2, inspection.features().get(1).parts().size());

		DatasetReadResult materialized = inspection.materialize(new HeightMapping("Elevation", HeightUnit.M));
		assertEquals(3, materialized.buildings().size());
		assertEquals(1, materialized.metadata().heightQuality().invalid());
		assertEquals(12, materialized.metadata().minHeightMeters());
		assertEquals(48, materialized.metadata().maxHeightMeters());
	}

	@Test
	void repairsSelfIntersectionExtractsPolygonFromCollectionAndSkipsOtherGeometry() throws Exception {
		Path input = write("damaged.geojson", """
				{"type":"FeatureCollection","features":[
				 {"type":"Feature","properties":{"h":12},"geometry":{"type":"Polygon","coordinates":[[[0,0],[2,2],[0,2],[2,0],[0,0]]]}},
				 {"type":"Feature","properties":{"h":14},"geometry":{"type":"GeometryCollection","geometries":[{"type":"Point","coordinates":[0,0]},{"type":"Polygon","coordinates":[[[3,0],[4,0],[4,1],[3,1],[3,0]]]}]}},
				 {"type":"Feature","properties":{"h":15},"geometry":{"type":"Polygon","coordinates":[[[5,0],[6,0],[7,0],[5,0]]]}},
				 {"type":"Feature","properties":{"h":16},"geometry":{"type":"Point","coordinates":[8,0]}},
				 {"type":"Feature","properties":{"h":17},"geometry":null}
				]}
				""");

		DatasetInspection inspection = reader.inspect(input, ImportOptions.defaults());

		assertEquals(5, inspection.featureCount());
		assertEquals(2, inspection.features().size());
		assertEquals(3, inspection.skippedInvalidGeometry());
		assertTrue(inspection.repairedGeometryCount() >= 1);
		assertEquals(1L, inspection.geometryTypes().get("GeometryCollection"));
		assertTrue(inspection.issues().stream().anyMatch(issue -> issue.code().contains("GEOMETRY")));
	}

	@Test
	void stableHashIgnoresJsonFormattingAndPropertyOrder() throws Exception {
		Path first = write("first.geojson", featureCollection("{\"a\":1,\"h\":12}"));
		Path second = write("second.geojson", featureCollection("{ \"h\" : 12.0, \"a\" : 1.0 }"));

		String firstId = reader.inspect(first, ImportOptions.defaults()).features().get(0).id();
		String secondId = reader.inspect(second, ImportOptions.defaults()).features().get(0).id();
		assertEquals(firstId, secondId);
		assertTrue(firstId.startsWith("hash:"));
	}

	@Test
	void transformsProjectedGeoJsonAndHonorsExplicitOverride() throws Exception {
		Path input = write("mercator.geojson", """
				{"type":"FeatureCollection","crs":{"type":"name","properties":{"name":"EPSG:3857"}},"features":[
				 {"type":"Feature","id":"p","properties":{"h":1},"geometry":{"type":"Polygon","coordinates":[[[12957580,4851410],[12957600,4851410],[12957600,4851430],[12957580,4851430],[12957580,4851410]]]}}
				]}
				""");
		DatasetInspection inspection = reader.inspect(input, ImportOptions.defaults());
		assertEquals(116.4, inspection.features().get(0).geometryWgs84().getCentroid().getX(), 2e-4);
		assertEquals(39.9, inspection.features().get(0).geometryWgs84().getCentroid().getY(), 2e-4);
		assertEquals("source:p", inspection.features().get(0).id());
	}

	@Test
	void supportsEmptyCollectionsButRejectsMalformedJsonAndBadCoordinates() throws Exception {
		DatasetInspection empty = reader.inspect(write("empty.geojson",
				"{\"type\":\"FeatureCollection\",\"features\":[]}"), ImportOptions.defaults());
		assertEquals(0, empty.featureCount());
		assertTrue(empty.boundsWgs84().isNull());

		DatasetImportException malformed = assertThrows(DatasetImportException.class,
				() -> reader.inspect(write("bad.geojson", "{bad"), ImportOptions.defaults()));
		assertEquals(DatasetErrorCode.INVALID_GEOJSON, malformed.code());

		Path outside = write("outside.geojson", featureCollection("{\"h\":1}").replace("[[0,0]", "[[500,0]"));
		DatasetImportException range = assertThrows(DatasetImportException.class,
				() -> reader.inspect(outside, ImportOptions.defaults()));
		assertEquals(DatasetErrorCode.COORDINATE_OUT_OF_RANGE, range.code());
	}

	@Test
	void duplicateSourceIdsAreDeterministicallyDisambiguatedAndInterruptionCancelsImport() throws Exception {
		Path duplicates = write("duplicates.geojson", """
				{"type":"FeatureCollection","features":[
				 {"type":"Feature","id":"same","properties":{"h":1},"geometry":{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,0]]]}},
				 {"type":"Feature","id":"same","properties":{"h":2},"geometry":{"type":"Polygon","coordinates":[[[2,0],[3,0],[3,1],[2,0]]]}}
				]}
				""");
		DatasetInspection first = reader.inspect(duplicates, ImportOptions.defaults());
		DatasetInspection second = reader.inspect(duplicates, ImportOptions.defaults());
		assertEquals(first.features().stream().map(SourceBuildingFeature::id).toList(),
				second.features().stream().map(SourceBuildingFeature::id).toList());
		assertEquals(2, first.features().stream().map(SourceBuildingFeature::id).distinct().count());
		assertTrue(first.issues().stream().anyMatch(issue -> issue.code().equals("DUPLICATE_FEATURE_ID")));

		Thread.currentThread().interrupt();
		try {
			DatasetImportException cancelled = assertThrows(DatasetImportException.class,
					() -> reader.inspect(duplicates, ImportOptions.defaults()));
			assertEquals(DatasetErrorCode.IMPORT_TIMEOUT, cancelled.code());
		} finally {
			Thread.interrupted();
		}
	}

	private Path write(String name, String content) throws Exception {
		Path path = temporaryDirectory.resolve(name);
		Files.writeString(path, content, UTF_8);
		return path;
	}

	private static String featureCollection(String properties) {
		return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":"
				+ properties + ",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}}]}";
	}
}
