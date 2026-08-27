import { AimOutlined, ReloadOutlined } from "@ant-design/icons";
import { Button, Space, Spin } from "antd";
import { useEffect, useRef, useState } from "react";

export type CesiumSource =
  | { kind: "footprints"; url: string; bounds: number[] }
  | { kind: "tileset"; url: string; bounds: number[]; focusBounds?: number[] };

interface Props {
  source: CesiumSource | null;
  label: string;
  height?: number;
  onReadyChange?: (ready: boolean) => void;
}

export function validWgs84Bounds(bounds?: number[]): bounds is [number, number, number, number] {
  if (!bounds || bounds.length !== 4 || !bounds.every(Number.isFinite)) return false;
  const [west, south, east, north] = bounds;
  return west >= -180 && east <= 180 && south >= -90 && north <= 90
    && west < east && south < north && east - west < 180 && north - south < 90;
}

export function webMercatorTileBounds(tileKey: string): [number, number, number, number] {
  const [zoom, x, y] = tileKey.split(",").map(Number);
  const scale = 2 ** zoom;
  const longitude = (tileX: number) => (tileX / scale) * 360 - 180;
  const latitude = (tileY: number) =>
    (Math.atan(Math.sinh(Math.PI * (1 - (2 * tileY) / scale))) * 180) / Math.PI;
  return [longitude(x), latitude(y + 1), longitude(x + 1), latitude(y)];
}

/**
 * Cesium 1.144 performs an unguarded `instanceof OffscreenCanvas` check while
 * constructing default materials. Windows WebKit exposes WebGL but not the
 * OffscreenCanvas global, so provide an identity-only constructor. Cesium does
 * not instantiate it in this workflow; the shim only makes the feature check
 * safe and leaves HTMLCanvasElement rendering unchanged.
 */
export function ensureOffscreenCanvasCompatibility(scope: Record<string, unknown> = globalThis) {
  if (!("OffscreenCanvas" in scope)) {
    Object.defineProperty(scope, "OffscreenCanvas", {
      configurable: true,
      value: class OffscreenCanvasCompatibility {}
    });
  }
}

export function CesiumViewport({ source, label, height = 440, onReadyChange }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const fit = useRef<(() => Promise<void>) | null>(null);
  const [status, setStatus] = useState<"idle" | "loading" | "ready" | "error">("idle");
  const [error, setError] = useState("");
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    const host = container.current;
    if (!source || !host) return;
    let disposed = false;
    let viewer: import("cesium").Viewer | undefined;
    let removeRenderError: (() => void) | undefined;
    setStatus("loading");
    setError("");
    onReadyChange?.(false);

    const load = async () => {
      ensureOffscreenCanvasCompatibility();
      const Cesium = await import("cesium");
      if (disposed) return;
      viewer = new Cesium.Viewer(host, {
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
      viewer.scene.globe.baseColor = Cesium.Color.fromCssColorString("#132334");
      viewer.scene.backgroundColor = Cesium.Color.fromCssColorString("#08121f");
      viewer.scene.globe.enableLighting = true;
      removeRenderError = viewer.scene.renderError.addEventListener((_scene, failure) => {
        if (!disposed) {
          setError(`Cesium 渲染失败：${failure.message}`);
          setStatus("error");
          onReadyChange?.(false);
        }
      });

      const primaryBounds = validWgs84Bounds(source.bounds) ? source.bounds : null;
      const requestedFocus = source.kind === "tileset" ? source.focusBounds : undefined;
      const focusBounds = validWgs84Bounds(requestedFocus) ? requestedFocus : primaryBounds;
      if (primaryBounds) {
        viewer.entities.add({
          name: "数据范围",
          rectangle: {
            coordinates: Cesium.Rectangle.fromDegrees(...primaryBounds),
            height: 0,
            fill: true,
            material: Cesium.Color.fromCssColorString("#1bd8d0").withAlpha(0.06),
            outline: true,
            outlineColor: Cesium.Color.fromCssColorString("#1bd8d0")
          }
        });
      }

      const flyToFocus = async () => {
        if (!viewer || viewer.isDestroyed()) return;
        if (focusBounds) {
          await viewer.camera.flyTo({
            destination: Cesium.Rectangle.fromDegrees(...focusBounds),
            duration: 0.35
          });
        }
        viewer.scene.requestRender();
      };
      fit.current = flyToFocus;

      if (source.kind === "footprints") {
        const footprints = await Cesium.GeoJsonDataSource.load(source.url, {
          clampToGround: false,
          fill: Cesium.Color.fromCssColorString("#43d7c2").withAlpha(0.32),
          stroke: Cesium.Color.fromCssColorString("#b6fff2"),
          strokeWidth: 1
        });
        if (disposed) return;
        viewer.dataSources.add(footprints);
        await flyToFocus();
      } else {
        const tileset = await Cesium.Cesium3DTileset.fromUrl(source.url);
        tileset.maximumScreenSpaceError = 2;
        viewer.scene.primitives.add(tileset);
        await flyToFocus();
        await waitForInitialTilesetLoad(tileset);
      }
      if (!disposed) {
        setStatus("ready");
        onReadyChange?.(true);
      }
    };

    load().catch((failure: unknown) => {
      if (!disposed) {
        const message = failure instanceof Error ? failure.message : String(failure);
        console.error("Cesium viewport failed", failure);
        setError(`无法加载空间预览：${message}`);
        setStatus("error");
        onReadyChange?.(false);
      }
    });

    return () => {
      disposed = true;
      fit.current = null;
      removeRenderError?.();
      if (viewer && !viewer.isDestroyed()) viewer.destroy();
      host.replaceChildren();
    };
  }, [source?.kind, source?.url, JSON.stringify(source?.bounds), JSON.stringify(source && "focusBounds" in source ? source.focusBounds : null), reloadKey]);

  return (
    <section className="map-shell" aria-label={label} style={{ height }}>
      <div ref={container} className="cesium-host" data-testid="cesium-host" data-ready={status === "ready"} />
      {status === "loading" && <div className="map-overlay"><Spin /><span>正在加载空间内容…</span></div>}
      {status === "error" && (
        <div className="map-overlay map-error" role="alert">
          <strong>地图加载失败</strong><span>{error}</span>
          <Button icon={<ReloadOutlined />} onClick={() => setReloadKey((value) => value + 1)}>重试</Button>
        </div>
      )}
      {status === "ready" && (
        <Space className="map-controls">
          <Button size="small" icon={<AimOutlined />} onClick={() => void fit.current?.()}>适配范围</Button>
          <Button size="small" icon={<ReloadOutlined />} onClick={() => setReloadKey((value) => value + 1)}>重置</Button>
        </Space>
      )}
    </section>
  );
}

function waitForInitialTilesetLoad(tileset: import("cesium").Cesium3DTileset, timeoutMilliseconds = 60_000) {
  if (tileset.tilesLoaded) return Promise.resolve();
  return new Promise<void>((resolve, reject) => {
    const timeout = window.setTimeout(() => {
      removeListener();
      reject(new Error("3D Tiles 内容加载超时"));
    }, timeoutMilliseconds);
    const removeListener = tileset.initialTilesLoaded.addEventListener(() => {
      window.clearTimeout(timeout);
      removeListener();
      resolve();
    });
  });
}
