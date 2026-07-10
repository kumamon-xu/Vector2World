package org.osm2world.viewer.model;

import java.util.HashSet;
import java.util.Observable;
import java.util.Set;

import javax.annotation.Nullable;

import org.osm2world.map_elevation.creation.BridgeTunnelEleCalculator;
import org.osm2world.map_elevation.creation.EleCalculator;
import org.osm2world.map_elevation.creation.TerrainInterpolator;
import org.osm2world.map_elevation.creation.ZeroInterpolator;
import org.osm2world.output.common.rendering.MutableCamera;
import org.osm2world.output.common.rendering.Projection;
import org.osm2world.scene.mesh.LevelOfDetail;
import org.osm2world.viewer.view.debug.DebugView;

public class RenderOptions extends Observable {

	public LevelOfDetail lod = LevelOfDetail.LOD4;

	public MutableCamera camera = null;
	public Projection projection = Defaults.PERSPECTIVE_PROJECTION;

	public Set<DebugView> activeDebugViews = new HashSet<>();

	private boolean wireframe = false;
	private boolean backfaceCulling = true;

	Class<? extends TerrainInterpolator> interpolatorClass = ZeroInterpolator.class;
	Class<? extends EleCalculator> eleCalculatorClass = BridgeTunnelEleCalculator.class;

	private int maxLevel = Integer.MAX_VALUE;

	public boolean isWireframe() {
		return wireframe;
	}
	public void setWireframe(boolean wireframe) {
		this.wireframe = wireframe;
	}
	public boolean isBackfaceCulling() {
		return backfaceCulling;
	}
	public void setBackfaceCulling(boolean backfaceCulling) {
		this.backfaceCulling = backfaceCulling;
	}

	public @Nullable LevelOfDetail getLod() {
		return lod;
	}
	public void setLod(LevelOfDetail lod) {
		this.lod = lod;
	}

	public Class<? extends TerrainInterpolator> getInterpolatorClass() {
		return interpolatorClass;
	}

	public void setInterpolatorClass(Class<? extends TerrainInterpolator> interpolatorClass) {
		this.interpolatorClass = interpolatorClass;
	}

	public Class<? extends EleCalculator> getEleCalculatorClass() {
		return eleCalculatorClass;
	}

	public void setEleCalculatorClass(Class<? extends EleCalculator> eleCalculatorClass) {
		this.eleCalculatorClass = eleCalculatorClass;
	}

	public int getMaxLevel() {
		return maxLevel;
	}

	public void setMaxLevel(int maxLevel) {
		this.maxLevel = maxLevel;
		this.setChanged();
		this.notifyObservers("maxLevel");
	}

}
