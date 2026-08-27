import {
  Cesium3DTileset,
  Color,
  Rectangle,
  Viewer
} from "cesium";
import "cesium/Build/Cesium/Widgets/widgets.css";
import "./style.css";

const params = new URLSearchParams(window.location.search);
const previewId = params.get("previewId");
const dataset = previewId ? "m2" : params.get("dataset") === "shp" ? "shp" : "geojson";
const select = document.querySelector("#datasetSelect");
const status = document.querySelector("#status");
const details = document.querySelector("#details");
select.value = dataset;

select.addEventListener("change", () => {
  const next = new URL(window.location.href);
  next.searchParams.set("dataset", select.value);
  window.location.assign(next);
});

const viewer = new Viewer("cesiumContainer", {
  animation: false,
  baseLayer: false,
  baseLayerPicker: false,
  fullscreenButton: false,
  geocoder: false,
  homeButton: false,
  infoBox: false,
  navigationHelpButton: false,
  sceneModePicker: false,
  selectionIndicator: false,
  timeline: false,
  scene3DOnly: true
});

viewer.scene.globe.baseColor = Color.fromCssColorString("#162233");
viewer.scene.backgroundColor = Color.fromCssColorString("#0b111b");

function waitForInitialTilesetLoad(tileset, timeoutMilliseconds = 60000) {
	if (tileset.tilesLoaded) return Promise.resolve();
	return new Promise((resolve, reject) => {
		let removeListener;
		const timeout = window.setTimeout(() => {
			removeListener?.();
			reject(new Error("3D Tiles content load timed out"));
		}, timeoutMilliseconds);
		removeListener = tileset.initialTilesLoaded.addEventListener(() => {
			window.clearTimeout(timeout);
			removeListener();
			resolve();
		});
	});
}

function webMercatorTileRectangle(tileKey) {
  const [zoom, x, y] = tileKey.split(",").map(Number);
  const scale = 2 ** zoom;
  const longitude = (tileX) => (tileX / scale) * 360 - 180;
  const latitude = (tileY) =>
    (Math.atan(Math.sinh(Math.PI * (1 - (2 * tileY) / scale))) * 180) /
    Math.PI;
  return Rectangle.fromDegrees(
    longitude(x),
    latitude(y + 1),
    longitude(x + 1),
    latitude(y)
  );
}

async function load() {
	if (previewId) {
		const metadata = await fetch(`/api/model-previews/${previewId}`).then((response) => {
			if (!response.ok) throw new Error(`preview HTTP ${response.status}`);
			return response.json();
		});
		const base = `/api/model-previews/${previewId}/files`;
		const report = await fetch(`${base}/preview-report.json`).then((response) => {
			if (!response.ok) throw new Error(`preview report HTTP ${response.status}`);
			return response.json();
		});
		const tileset = await Cesium3DTileset.fromUrl(`${base}/tileset.json`);
		tileset.maximumScreenSpaceError = 1;
		viewer.scene.primitives.add(tileset);
		const [west, south, east, north] = metadata.boundsWgs84;
		viewer.entities.add({
			name: "代表样本范围",
			rectangle: {
				coordinates: Rectangle.fromDegrees(west, south, east, north),
				height: 0,
				fill: true,
				material: Color.CYAN.withAlpha(0.05),
				outline: true,
				outlineColor: Color.CYAN
			}
		});
		const focusBounds = report.focusBoundsWgs84 ?? metadata.boundsWgs84;
		await viewer.camera.flyTo({
			destination: Rectangle.fromDegrees(...focusBounds),
			duration: 0
		});
		viewer.scene.requestRender();
		await waitForInitialTilesetLoad(tileset);
		viewer.scene.requestRender();
		status.textContent = `READY · M2 · ${metadata.modeledBuildings} Buildings`;
		status.dataset.ready = "true";
		details.textContent = JSON.stringify({
			ruleVersion: metadata.config.ruleVersion,
			stylePreset: metadata.config.stylePreset,
			roofMode: metadata.config.roofMode,
			selectedBuildings: metadata.selectedBuildings,
			modeledBuildings: metadata.modeledBuildings,
			meshCount: metadata.meshCount,
			assetVersion: report.validation.assetVersion,
			glbCount: report.validation.glbCount,
			ruleOutputHash: metadata.ruleOutputHash
		}, null, 2);
		window.__VECTOR2WORLD_M2__ = { ready: true, contentLoaded: true, metadata, report };
		return;
	}
  const base = `/generated/${dataset}`;
  const [manifest, report] = await Promise.all([
    fetch(`${base}/manifest.json`).then((response) => {
      if (!response.ok) throw new Error(`manifest HTTP ${response.status}`);
      return response.json();
    }),
    fetch(`${base}/generation-report.json`).then((response) => {
      if (!response.ok) throw new Error(`report HTTP ${response.status}`);
      return response.json();
    })
  ]);

  const tileset = await Cesium3DTileset.fromUrl(`${base}/tileset.json`);
  viewer.scene.primitives.add(tileset);

  const [west, south, east, north] = manifest.boundsWgs84;
  viewer.entities.add({
    name: "源数据范围",
    rectangle: {
      coordinates: Rectangle.fromDegrees(west, south, east, north),
      height: 0,
      fill: true,
      material: Color.CYAN.withAlpha(0.08),
      outline: true,
      outlineColor: Color.CYAN
    }
  });

  await viewer.zoomTo(tileset);
  await viewer.camera.flyTo({
    destination: webMercatorTileRectangle(manifest.tiles[0]),
    duration: 0
  });
  const center = Rectangle.center(Rectangle.fromDegrees(west, south, east, north));

  status.textContent = `READY · ${dataset.toUpperCase()} · ${report.tileCount} Tiles`;
  status.dataset.ready = "true";
  details.textContent = JSON.stringify({
    inputFeatures: report.inputFeatures,
    modeledBuildings: report.modeledBuildings,
    tileCount: report.tileCount,
    lods: report.lods,
    assetVersion: report.validation.assetVersion,
    glbCount: report.validation.glbCount,
    centerRadians: [center.longitude, center.latitude]
  }, null, 2);

  window.__VECTOR2WORLD_M0__ = {
    ready: true,
    dataset,
    report,
    manifest
  };
}

load().catch((error) => {
  console.error(error);
  status.textContent = `FAILED · ${error.message}`;
  status.dataset.ready = "false";
  const failure = { ready: false, dataset, error: error.message };
  if (previewId) window.__VECTOR2WORLD_M2__ = failure;
  else window.__VECTOR2WORLD_M0__ = failure;
});
