package org.osm2world.viewer.view.debug;

import static org.apache.commons.lang3.math.NumberUtils.toInt;
import static org.osm2world.output.jogl.JOGLRenderingParameters.Winding.CCW;
import static org.osm2world.util.FaultTolerantIterationUtil.forEach;

import java.util.Arrays;

import org.osm2world.output.common.lighting.GlobalLightingParameters;
import org.osm2world.output.common.rendering.Camera;
import org.osm2world.output.common.rendering.Projection;
import org.osm2world.output.jogl.JOGLOutput;
import org.osm2world.output.jogl.JOGLRenderingParameters;
import org.osm2world.scene.Scene;
import org.osm2world.scene.mesh.MeshStore;
import org.osm2world.scene.mesh.MeshWithMetadata;
import org.osm2world.viewer.model.RenderOptions;
import org.osm2world.world.data.WorldObject;

public class WorldObjectView extends DebugView {

	private final RenderOptions renderOptions;

	public WorldObjectView(RenderOptions renderOptions) {
		super("World objects", "shows the world objects");
		this.renderOptions = renderOptions;
	}

	@Override
	protected void updateOutput(JOGLOutput output, boolean viewChanged, Camera camera, Projection projection) {

		setParameters(output);

		if (output.isFinished()) return;

		output.setXZBoundary(scene.getBoundary());

		// write scene to MeshStore first to allow filtering
		MeshStore meshStore = Scene.sceneToMeshes(scene, config, this::includeMesh);

		forEach(meshStore.meshesWithMetadata(), output::drawMesh);

	}

	/** Decides whether to include a mesh in the rendering based on the current {@link #renderOptions}. */
	private boolean includeMesh(WorldObject worldObject, MeshWithMetadata mesh) {
		int maxLevel = renderOptions.getMaxLevel();
		if (maxLevel != Integer.MAX_VALUE) {
			Object levelValue = mesh.metadata().extraProperties().get("level");
			if (levelValue instanceof String levelList) {
				String[] levels = levelList.split(";");
				return Arrays.stream(levels).allMatch(level -> toInt(level, Integer.MAX_VALUE) <= maxLevel);
			}
		}
		return true;
	}

	private void setParameters(final JOGLOutput target) {

		boolean drawBoundingBox = config.getBoolean("drawBoundingBox", false);
		boolean shadowVolumes = "shadowVolumes".equals(config.getString("shadowImplementation"))
				|| "both".equals(config.getString("shadowImplementation"));
		boolean shadowMaps = "shadowMap".equals(config.getString("shadowImplementation"))
				|| "both".equals(config.getString("shadowImplementation"));
		int shadowMapWidth = config.getInt("shadowMapWidth", 4096);
		int shadowMapHeight = config.getInt("shadowMapHeight", 4096);
		int shadowMapCameraFrustumPadding = config.getInt("shadowMapCameraFrustumPadding", 8);
		boolean useSSAO = "true".equals(config.getString("useSSAO"));
		int SSAOkernelSize = config.getInt("SSAOkernelSize", 16);
		float SSAOradius = config.getFloat("SSAOradius", 1);
		boolean overwriteProjectionClippingPlanes = "true".equals(config.getString("overwriteProjectionClippingPlanes"));
		target.setRenderingParameters(new JOGLRenderingParameters(
				renderOptions.isBackfaceCulling() ? CCW : null,
    			renderOptions.isWireframe(), true, drawBoundingBox, shadowVolumes, shadowMaps, shadowMapWidth, shadowMapHeight,
    			shadowMapCameraFrustumPadding, useSSAO, SSAOkernelSize, SSAOradius, overwriteProjectionClippingPlanes));

		target.setGlobalLightingParameters(GlobalLightingParameters.DEFAULT);

	}

}
