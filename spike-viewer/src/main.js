import {
  Cesium3DTileset,
  Color,
  Rectangle,
  Viewer
} from "cesium";
import "cesium/Build/Cesium/Widgets/widgets.css";
import "./style.css";

const params = new URLSearchParams(window.location.search);
const dataset = params.get("dataset") === "shp" ? "shp" : "geojson";
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
  window.__VECTOR2WORLD_M0__ = { ready: false, dataset, error: error.message };
});
