import { defineConfig, loadEnv } from "vite";
import { viteStaticCopy } from "vite-plugin-static-copy";

const cesiumDirectories = ["Workers", "ThirdParty", "Assets", "Widgets"];

export default defineConfig(({ command, mode }) => {
  const env = loadEnv(mode, process.cwd(), "VITE_");
  const proxy = env.VITE_API_TARGET ? { "/api": { target: env.VITE_API_TARGET, changeOrigin: false } } : undefined;
  return ({
  define: {
    CESIUM_BASE_URL: JSON.stringify(
      command === "serve"
        ? "/node_modules/cesium/Build/Cesium"
        : "/cesiumStatic"
    )
  },
  plugins: [
    viteStaticCopy({
      targets: cesiumDirectories.map((directory) => ({
        src: `node_modules/cesium/Build/Cesium/${directory}/**/*`,
        dest: `cesiumStatic/${directory}`,
        rename: { stripBase: 5 }
      }))
    })
  ],
  server: { proxy },
  preview: { port: 4173, proxy }
  });
});
