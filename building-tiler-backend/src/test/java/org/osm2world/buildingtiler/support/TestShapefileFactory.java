package org.osm2world.buildingtiler.support;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.geotools.api.data.Transaction;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

public final class TestShapefileFactory {

	private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

	private TestShapefileFactory() {}

	public static Path create(Path directory, String baseName, Charset charset) throws Exception {
		Files.createDirectories(directory);
		Path shp = directory.resolve(baseName + ".shp");
		Map<String, Object> parameters = new LinkedHashMap<>();
		parameters.put("url", shp.toUri().toURL());
		parameters.put("create spatial index", Boolean.TRUE);
		ShapefileDataStore store = (ShapefileDataStore) new ShapefileDataStoreFactory()
				.createNewDataStore(parameters);
		store.setCharset(charset);

		SimpleFeatureTypeBuilder type = new SimpleFeatureTypeBuilder();
		type.setName(baseName);
		type.setCRS(CRS.decode("EPSG:4326", true));
		type.add("the_geom", Polygon.class);
		type.add("Elevation", Double.class);
		type.length(64).add("NAME", String.class);
		SimpleFeatureType schema = type.buildFeatureType();
		store.createSchema(schema);

		try (var writer = store.getFeatureWriterAppend(Transaction.AUTO_COMMIT)) {
			var feature = writer.next();
			feature.setAttribute("the_geom", polygonWithHole());
			feature.setAttribute("Elevation", 1234.0);
			feature.setAttribute("NAME", "测试建筑");
			writer.write();
		} finally {
			store.dispose();
		}
		Files.writeString(directory.resolve(baseName + ".cpg"), charset.name(), UTF_8);
		return shp;
	}

	public static byte[] zip(Path shapefile, String omittedExtension) throws IOException {
		String fileName = shapefile.getFileName().toString();
		String base = fileName.substring(0, fileName.lastIndexOf('.'));
		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			 ZipOutputStream zip = new ZipOutputStream(bytes, UTF_8);
			 var files = Files.list(shapefile.getParent())) {
			for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
				String name = file.getFileName().toString();
				if (!name.toLowerCase(Locale.ROOT).startsWith(base.toLowerCase(Locale.ROOT) + ".")) continue;
				if (omittedExtension != null && name.toLowerCase(Locale.ROOT).endsWith(omittedExtension)) continue;
				zip.putNextEntry(new ZipEntry(name));
				Files.copy(file, zip);
				zip.closeEntry();
			}
			zip.finish();
			return bytes.toByteArray();
		}
	}

	private static Polygon polygonWithHole() {
		LinearRing shell = ring(new Coordinate[] {
				new Coordinate(116.39, 39.89), new Coordinate(116.41, 39.89),
				new Coordinate(116.41, 39.91), new Coordinate(116.39, 39.91),
				new Coordinate(116.39, 39.89) });
		LinearRing hole = ring(new Coordinate[] {
				new Coordinate(116.395, 39.895), new Coordinate(116.395, 39.90),
				new Coordinate(116.40, 39.90), new Coordinate(116.40, 39.895),
				new Coordinate(116.395, 39.895) });
		return GEOMETRY_FACTORY.createPolygon(shell, new LinearRing[] { hole });
	}

	private static LinearRing ring(Coordinate[] coordinates) {
		return GEOMETRY_FACTORY.createLinearRing(coordinates);
	}
}
