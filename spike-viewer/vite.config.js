import { defineConfig } from "vite";
import { viteStaticCopy } from "vite-plugin-static-copy";

const cesiumDirectories = ["Workers", "ThirdParty", "Assets", "Widgets"];

export default defineConfig(({ command }) => ({
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
	server: {
		proxy: {
			"/api": "http://127.0.0.1:18080"
		}
	}
}));
