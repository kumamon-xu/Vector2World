import { afterEach, describe, expect, it, vi } from "vitest";
import { DEFAULT_CONFIG } from "../domain";
import { api, ApiClientError } from "./client";

afterEach(() => vi.unstubAllGlobals());

describe("API client contract", () => {
  it("sends generation-only tiling fields without preview-only fields", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: "job-1" }), {
      status: 202, headers: { "Content-Type": "application/json" }
    }));
    vi.stubGlobal("fetch", fetchMock);
    await api.createJob("dataset-1", { ...DEFAULT_CONFIG, heightField: "Elevation", heightUnit: "m" });
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const body = JSON.parse(String(init.body));
    expect(body).toMatchObject({ datasetId: "dataset-1", heightField: "Elevation", heightUnit: "m", lods: [2], outputFormats: ["3DTILES"] });
    expect(body).not.toHaveProperty("lod");
    expect(body).not.toHaveProperty("sampleSize");
  });

  it("turns dataset expiry into an actionable client error", async () => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation(() => Promise.resolve(
      new Response(JSON.stringify({ code: "DATASET_NOT_FOUND", message: "Dataset not found" }), {
        status: 404, headers: { "Content-Type": "application/json" }
      })
    )));
    await expect(api.dataset("expired")).rejects.toEqual(expect.objectContaining({
      name: "ApiClientError", status: 404, code: "DATASET_NOT_FOUND"
    } satisfies Partial<ApiClientError>));
    await expect(api.dataset("expired")).rejects.toThrow("请返回第一步重新导入");
  });
});
