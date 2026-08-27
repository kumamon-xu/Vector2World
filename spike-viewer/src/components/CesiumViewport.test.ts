import { describe, expect, it } from "vitest";
import { validWgs84Bounds, webMercatorTileBounds } from "./CesiumViewport";

describe("Cesium spatial helpers", () => {
  it("accepts finite non-wrapping WGS84 bounds", () => {
    expect(validWgs84Bounds([113, 22, 114, 23])).toBe(true);
    expect(validWgs84Bounds([114, 23, 113, 22])).toBe(false);
    expect(validWgs84Bounds([-200, 22, 114, 23])).toBe(false);
  });

  it("converts Web Mercator tile keys to geographic bounds", () => {
    const bounds = webMercatorTileBounds("0,0,0");
    expect(bounds[0]).toBe(-180); expect(bounds[1]).toBeCloseTo(-85.0511288);
    expect(bounds[2]).toBe(180); expect(bounds[3]).toBeCloseTo(85.0511288);
  });
});
