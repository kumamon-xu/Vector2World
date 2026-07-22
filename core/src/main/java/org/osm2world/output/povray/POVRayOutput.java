package org.osm2world.output.povray;

import static java.util.Locale.ROOT;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.*;

import javax.annotation.Nonnull;
import javax.imageio.ImageIO;

import org.osm2world.math.VectorXYZ;
import org.osm2world.math.VectorXZ;
import org.osm2world.math.shapes.TriangleXYZ;
import org.osm2world.output.common.AbstractOutput;
import org.osm2world.output.common.lighting.GlobalLightingParameters;
import org.osm2world.output.common.rendering.Camera;
import org.osm2world.output.common.rendering.OrthographicProjection;
import org.osm2world.output.common.rendering.Projection;
import org.osm2world.scene.Scene;
import org.osm2world.scene.color.Color;
import org.osm2world.scene.material.*;
import org.osm2world.scene.mesh.MeshStore;
import org.osm2world.scene.mesh.TriangleGeometry;
import org.osm2world.util.GlobalValues;

/**
 * Writes models to files for the POVRay ray tracer.
 */
public class POVRayOutput extends AbstractOutput {

	private static final boolean TERRAIN_PLANE = true;
	protected static final float AMBIENT_FACTOR = 0.5f;

	private static final String INDENT = "  ";

	// this is approximately one millimeter
	private static final double SMALL_OFFSET = 1e-3;

	private final PrintStream output;
	private final Camera camera;
	private final Projection projection;

	private final Map<TextureData, String> textureNames = new HashMap<>();

	public POVRayOutput(File file, Camera camera, Projection projection) throws FileNotFoundException {
		this(new PrintStream(file), camera, projection);
	}

	public POVRayOutput(PrintStream output, Camera camera, Projection projection) {
		this.output = output;
		this.camera = camera;
		this.projection = projection;
	}

	@Override
	public void outputScene(Scene scene) {

		appendCommentHeader();

		append("\n#include \"textures.inc\"\n#include \"colors.inc\"\n");
		append("#include \"osm2world_definitions.inc\"\n\n");

		if (camera != null && projection != null) {
			appendCameraDefinition(camera, projection);
		}

		append("//\n// global scene parameters\n//\n\n");

		appendLightingDefinition(GlobalLightingParameters.DEFAULT);

		appendDefaultParameterValue("season", "\"summer\"");
		appendDefaultParameterValue("time", "\"day\"");

		append("//\n// material and object definitions\n//\n\n");

		appendDefaultParameterValue("sky_sphere_def",
				"sky_sphere {\n pigment { Blue_Sky3 }\n} ");
		append("sky_sphere {sky_sphere_def}\n\n");

		Material terrainMaterial = DefaultMaterials.TERRAIN_DEFAULT.get(config.mapStyle());

		Set<Material> materials = new HashSet<>(Set.of(terrainMaterial));
		scene.getMeshes().stream().map(it -> it.material).forEach(materials::add);
		appendMaterialDefinitions(materials);

		if (TERRAIN_PLANE) {

			append("//\n// empty ground around the scene\n//\n\n");

			append("difference {\n");
			append("  plane { y, -0.001 }\n  ");
			VectorXZ[] boundary = scene.getBoundary().vertices().toArray(new VectorXZ[0]);
			appendPrism( -100, 1, boundary);
			append("\n");
			appendMaterialOrName(terrainMaterial);
			append("\n}\n\n");

		}

		append("\n\n//\n//Map data\n//\n\n");

		MeshStore meshStore = Scene.sceneToMeshes(scene, this.getConfig(), null);

		for (var mesh : meshStore.meshes()) {

			TriangleGeometry triangleGeometry = mesh.geometry.asTriangles();
			this.drawTriangles(mesh.material, triangleGeometry.triangles, triangleGeometry.texCoords);

		}

		output.close();
	}

	//	int openBrackets = 0;
//
//	/**
//	 * appends indentation based on {@link #INDENT} and {@link #openBrackets}
//	 */
//	private void appendIndent() {
//		for (int i=0; i<openBrackets; i++) {
//			append(INDENT);
//		}
//	}

	private void append(String code) {
		output.print(code);
//		if (code.contains("union") && openBrackets > 0) {
//			System.out.println(openBrackets);
//		}
//		for (int i=0; i<code.length(); i++) {
//			char c = code.charAt(i);
//			if (c == '{') {
//				openBrackets++;
//			} else if (c == '}') {
//				openBrackets--;
//			}
//		}
	}

	private void append(double value) {
		output.print(value);
	}

//	private final LinkedList<StringBuilder> stack = new LinkedList<StringBuilder>();
//
//	int openBrackets = 0;
//
//	private void append(int value) {
//		stack.peek().append(value);
//	}
//
//	private void append(double value) {
//		stack.peek().append(value);
//	}
//
//	private void startBlock(String s) {
//		StringBuilder newBlock = new StringBuilder(s + "{");
//		stack.push(newBlock);
//	}
//
//	private void endBlock(String block) {
//		StringBuilder closedBlock = stack.poll();
//		if (stack.isEmpty()) {
//			output.append(closedBlock);
//		} else {
//			stack.peek().append(closedBlock);
//		}
//	}

	private void appendDefaultParameterValue(String name, String value) {

		append("#ifndef (" + name + ")\n");
		append("#declare " + name + " = " + value);
		append("\n#end\n\n");

	}

	private void appendCommentHeader() {

		append("/*\n"
				+ " * This file was created by OSM2World "
				+ GlobalValues.VERSION_STRING + " - "
				+ GlobalValues.OSM2WORLD_URI + "\n"
				+ " * \n"
				+ " * Make sure that a \"osm2world_definitions.inc\" file exists!\n"
				+ " * You can start with the one in the \"resources\" folder from your\n"
				+ " * OSM2World installation or even just create an empty file.\n"
				+ " */\n");

	}

	private void appendLightingDefinition(GlobalLightingParameters parameters) {

		append(String.format(ROOT,
				"global_settings { ambient_light rgb <%f,%f,%f> }\n",
				parameters.globalAmbientColor.getRed() / 255f,
				parameters.globalAmbientColor.getGreen() / 255f,
				parameters.globalAmbientColor.getBlue() / 255f));

		append(String.format(ROOT,
				"light_source{ <%f,%f,%f> color rgb <%f,%f,%f> parallel point_at <0,0,0> fade_power 0 }\n\n",
				parameters.lightFromDirection.x * 100000,
				parameters.lightFromDirection.y * 100000,
				parameters.lightFromDirection.z * 100000,
				parameters.lightColorDiffuse.getRed() / 255f,
				parameters.lightColorDiffuse.getGreen() / 255f,
				parameters.lightColorDiffuse.getBlue() / 255f));

	}

	private void appendCameraDefinition(Camera camera, Projection projection) {

		append("camera {");

		if (projection.orthographic()) {
			append("\n  orthographic");
		}

		append("\n  location ");
		appendVector(camera.pos());

		if (projection instanceof OrthographicProjection proj) {

			append("\n  right ");
			double width = proj.volumeWidth();
			appendVector(camera.getRight().mult(width).invert()); //invert compensates for left-handed vs. right-handed coordinates

			append("\n  up ");
			VectorXYZ up = camera.up();
			appendVector(up.mult(proj.volumeHeight()));

			append("\n  look_at ");
			appendVector(camera.lookAt());

		} else {

			append("\n  look_at  ");
			appendVector(camera.lookAt());

			append("\n  sky ");
			appendVector(camera.up());

		}

		append("\n}\n\n");

	}

	private void appendMaterialDefinitions(Collection<Material> materials) {

		for (Material material : materials) {

			String uniqueName = config.mapStyle().getMaterialName(material);

			if (uniqueName == null) continue;

			String name = "texture_" + uniqueName;

			append("#ifndef (" + name + ")\n");
			append("#declare " + name + " = ");
			appendMaterial(material);
			append("#end\n\n");

			if (material.textureLayers().size() == 1) {

				TextureLayer layer = material.textureLayers().get(0);
				TextureData td = layer.baseColorTexture;

				if (!layer.colorable) {
					textureNames.put(td, uniqueName);
				}

			}

		}

	}

	private void drawTriangles(@Nonnull Material material,
							  @Nonnull List<? extends TriangleXYZ> triangles,
							  @Nonnull List<List<VectorXZ>> texCoordLists) {

		if (material.textureLayers().size() > 1) {

			int count = 0;

			for (TextureLayer textureLayer : material.textureLayers()) {

				if(!(textureLayer.baseColorTexture instanceof TextTexture)) { //temporarily ignore TextTextureData layers
					append("mesh {\n");

					drawTriangleMesh(triangles, texCoordLists.get(count), count);

					append("  uv_mapping ");
					appendMaterial(material, textureLayer.baseColorTexture, textureLayer.colorable);

					if (count > 0)
						append("  no_shadow");
					append("}\n");
					count++;
				}
			}
		} else {

				append("mesh {\n");

				if (!texCoordLists.isEmpty()) {
					drawTriangleMesh(triangles, texCoordLists.get(0), 0);
				} else {
					for (TriangleXYZ triangle : triangles) {
						append(INDENT);
						appendTriangle(triangle.v1, triangle.v2, triangle.v3);
					}
				}

				append(" uv_mapping ");
				appendMaterialOrName(material);

				append("}\n");

		}
	}

	private void drawTriangleMesh(Collection<? extends TriangleXYZ> triangles,
			List<VectorXZ> texCoordList, int depth) {

		Iterator<? extends TriangleXYZ> itr1 = triangles.iterator();
		Iterator<VectorXZ> itr2 = texCoordList.iterator();

		while (itr1.hasNext()) {

			TriangleXYZ triangle = itr1.next();
			VectorXYZ normal = triangle.getNormal();
			VectorXZ tex1 = itr2.next();
			VectorXZ tex2 = itr2.next();
			VectorXZ tex3 = itr2.next();

			append(INDENT);

			if (depth > 0) {

				normal = normal.mult(depth*SMALL_OFFSET);
				appendTriangle(
						triangle.v1.add(normal),
						triangle.v2.add(normal),
						triangle.v3.add(normal),
						null, null, null, tex1, tex2, tex3, false, true);

			} else {

				appendTriangle(triangle.v1, triangle.v2, triangle.v3,
						null, null, null, tex1, tex2, tex3, false, true);
			}
		}
	}

	private void appendTriangle(VectorXYZ a, VectorXYZ b, VectorXYZ c) {

		appendTriangle(a, b, c, null, null, null, false);
	}

	private void appendTriangle(
			VectorXYZ a, VectorXYZ b, VectorXYZ c,
			VectorXYZ na, VectorXYZ nb, VectorXYZ nc,
			boolean smooth) {

		appendTriangle(a, b, c, na, nb, nc, null, null, null, smooth, false);
	}

	private void appendTriangle(
			VectorXYZ a, VectorXYZ b, VectorXYZ c,
			VectorXYZ na, VectorXYZ nb, VectorXYZ nc,
			VectorXZ ta, VectorXZ tb, VectorXZ tc,
			boolean smooth, boolean texture) {

		// append the triangle

		if (smooth) append("smooth_");
		append("triangle { ");
		appendVector(a);
		if (smooth) {
			append(", ");
			appendVector(na);
		}
		append(", ");
		appendVector(b);
		if (smooth) {
			append(", ");
			appendVector(nb);
		}
		append(", ");
		appendVector(c);
		if (smooth) {
			append(", ");
			appendVector(nc);
		}

		if (texture) {
			/*
			append(" uv_vectors ");
			appendInverseVector(ta);
			append(", ");
			appendInverseVector(tb);
			append(", ");
			appendInverseVector(tc);
			*/

			append(" uv_vectors ");
			appendVector(ta);
			append(", ");
			appendVector(tb);
			append(", ");
			appendVector(tc);
		}

		append("}\n");
	}


	/**
	 * Adds a color. Syntax is "color rgb &lt;x, y, z>".
	 */
	private void appendRGBColor(Color color) {

		append("color rgb ");
		appendVector(
				color.getRed()/255f,
				color.getGreen()/255f,
				color.getBlue()/255f);

	}

	private void appendMaterialOrName(Material material) {

		String materialName = null;

		if (material.textureLayers().size() == 1) {
			materialName = textureNames.get(material.textureLayers().get(0).baseColorTexture);
		}

		if (materialName != null) {
			append(" texture { texture_" + materialName + " }");
		} else {
			appendMaterial(material);
		}

	}

	private void appendMaterial(Material material) {

		if (material.textureLayers().isEmpty()) {

			append("  texture {\n");
			append("    pigment { ");
			appendRGBColor(material.color());
			append(" }\n    finish {\n");
			append("      ambient " + AMBIENT_FACTOR + "\n");
			append("      diffuse " + (1 - AMBIENT_FACTOR) + "\n");
			append("    }\n");
			append("  }\n");

		} else {

			for (TextureLayer textureLayer : material.textureLayers()) {
				if(!(textureLayer.baseColorTexture instanceof TextTexture)) { //temporarily ignore TextTextureData layers
					appendMaterial(material, textureLayer.baseColorTexture, textureLayer.colorable);
				}
			}
		}
	}


	private void appendMaterial(Material material, TextureData textureData, boolean colorable) {

			String textureName = textureNames.get(textureData);

			if (textureName == null) {

				if (colorable) {
					append("  texture {\n");
					append("    pigment { ");
					appendRGBColor(material.color());
					append(" }\n    finish {\n");
					append("      ambient " + AMBIENT_FACTOR + "\n");
					append("      diffuse " + (1 - AMBIENT_FACTOR) + "\n");
					append("    }\n");
					append("  }\n");
				}

				append("  texture {\n");
				append("    pigment { ");
				appendImageMap(textureData, colorable);
				append(" }\n    finish {\n");
				append("      ambient " + AMBIENT_FACTOR + "\n");
				append("      diffuse " + (1 - AMBIENT_FACTOR) + "\n");
				append("    }\n");
				append("  }\n");

			} else {

				append("  texture { texture_" + textureName + "}");
			}
	}


	private void appendImageMap(TextureData textureData, boolean colorable) {

			append("        image_map {\n");

			try {
				File textureFile = getTextureFile(textureData);
				if (textureFile.getName().toLowerCase().endsWith("png")) {
					append("             png \"" + textureFile + "\"\n");
				} else {
					append("             jpeg \"" + textureFile + "\"\n");
				}

				if (colorable) {
					append("             filter all 1.0\n");
				}
			} catch (IOException e) {
				System.err.println("Could not append image_map for texture " + textureData + ":" + e);
			}

			append("\n          }");
	}

	/**
	 * Adds a vector to the String built by a StringBuilder.
	 * Syntax is "&lt;x, y, z>".
	 */
	private void appendVector(float x, float y, float z) {

		if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(z)) {
			throw new IllegalArgumentException("NaN vector " + x + ", " + y + ", " + z);
		}

		append("<");
		append(x);
		append(", ");
		append(y);
		append(", ");
		append(z);
		append(">");

	}

	private void appendVector(double x, double y, double z) {

		if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
			throw new IllegalArgumentException("NaN vector " + x + ", " + y + ", " + z);
		}

		append("<");
		append(x);
		append(", ");
		append(y);
		append(", ");
		append(z);
		append(">");

	}

	/**
	 * alternative to {@link #appendVector(double, double)}
	 * using a vector object as parameter instead of individual coordinates
	 */
	private void appendVector(VectorXYZ vector) {
		appendVector(vector.getX(), vector.getY(), vector.getZ());
	}

	/**
	 * Adds a vector to the String built by a StringBuilder.
	 * Syntax is "&lt;v1, v2>".
	 */
	private void appendVector(double x, double z) {

		append("<");
		append(x);
		append(", ");
		append(z);
		append(">");

	}

	/**
	 * alternative to {@link #appendVector(double, double)}
	 * using a vector object as parameter instead of individual coordinates
	 */
	private void appendVector(VectorXZ vector) {
		appendVector(vector.x, vector.z);
	}

	private void appendPrism(float y1, float y2, VectorXZ... vs) {

		append("prism {\n  ");
		append(y1);
		append(", ");
		append(y2);
		append(", ");
		append(vs.length);
		append(",\n  ");
		for (VectorXZ v : vs) {
			appendVector(v);
		}
		append("\n}");

	}

	private final Map<TextureData, File> textureFileMap = new HashMap<>();

	private File getTextureFile(TextureData texture) throws IOException {

		if (!textureFileMap.containsKey(texture)) {

			if (texture instanceof RasterImageFileTexture rasterImageFileTexture) {
				textureFileMap.put(texture, rasterImageFileTexture.getFile());
			} else {

				BufferedImage image = texture.getBufferedImage();
				String prefix = "o2w-";

				File textureFile = File.createTempFile(prefix, ".png");
				ImageIO.write(image, "png", textureFile);

				textureFileMap.put(texture, textureFile);

			}

		}

		return textureFileMap.get(texture);

	}

}

