import type {
  ApiErrorBody,
  DatasetResponse,
  GenerationManifest,
  GenerationReport,
  JobResponse,
  ModelingConfig,
  ProductAbout,
  PreviewReport,
  PreviewResponse
} from "../domain";

export class ApiClientError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details: Record<string, unknown>;

  constructor(status: number, body: ApiErrorBody) {
    super(actionableMessage(status, body));
    this.name = "ApiClientError";
    this.status = status;
    this.code = body.code ?? "HTTP_ERROR";
    this.details = body.details ?? {};
  }
}

function actionableMessage(status: number, body: ApiErrorBody): string {
  const source = body.message || `服务请求失败（HTTP ${status}）`;
  const suggestions: Record<string, string> = {
    CRS_REQUIRED: "请在导入高级选项中明确填写源坐标系，例如 EPSG:4326。",
    SHAPEFILE_COMPONENT_MISSING: "请将 .shp、.shx、.dbf、.prj 一起打包为 ZIP。",
    UPLOAD_TOO_LARGE: "请拆分数据或提高服务端上传限制。",
    IMPORT_RESOURCE_LIMIT: "数据复杂度超过当前 JVM 的安全内存余量，请拆分数据后分批导入。",
    IMPORT_CANCELLED: "导入已取消，临时文件已清理。",
    INVALID_HEIGHT_FIELD: "请选择包含有限正数的高度字段。",
    DATASET_NOT_FOUND: "数据集已失效，请返回第一步重新导入。",
    GENERATION_JOB_NOT_FOUND: "任务已失效，请重新发起生成。"
  };
  return `${source}${body.code && suggestions[body.code] ? ` ${suggestions[body.code]}` : ""}`;
}

async function parseBody(response: Response): Promise<unknown> {
  const contentType = response.headers.get("content-type") || "";
  if (contentType.includes("json")) return response.json();
  return response.text();
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { Accept: "application/json", ...init?.headers }
  });
  const body = await parseBody(response);
  if (!response.ok) {
    throw new ApiClientError(response.status, typeof body === "object" && body ? body as ApiErrorBody : {});
  }
  return body as T;
}

export interface UploadOptions {
  sourceCrs?: string;
  dbfCharset?: string;
}

export function uploadDataset(
  file: File,
  options: UploadOptions,
  onProgress: (percent: number) => void,
  signal: AbortSignal
): Promise<DatasetResponse> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const form = new FormData();
    form.append("file", file);
    if (options.sourceCrs) form.append("sourceCrs", options.sourceCrs);
    if (options.dbfCharset) form.append("dbfCharset", options.dbfCharset);
    xhr.open("POST", "/api/datasets");
    xhr.setRequestHeader("Accept", "application/json");
    xhr.upload.addEventListener("progress", (event) => {
      if (event.lengthComputable) onProgress(Math.round((event.loaded / event.total) * 100));
    });
    xhr.addEventListener("load", () => {
      let body: unknown = {};
      try { body = JSON.parse(xhr.responseText); } catch { /* server returned no JSON */ }
      if (xhr.status >= 200 && xhr.status < 300) resolve(body as DatasetResponse);
      else reject(new ApiClientError(xhr.status, body as ApiErrorBody));
    });
    xhr.addEventListener("error", () => reject(new Error("无法连接本地 Vector2World 服务。")));
    xhr.addEventListener("abort", () => reject(new DOMException("上传已取消", "AbortError")));
    signal.addEventListener("abort", () => xhr.abort(), { once: true });
    xhr.send(form);
  });
}

export const api = {
  about: () => request<ProductAbout>("/api/system/about"),
  health: () => request<{ status: "UP"; timestamp: string; pid: number }>("/api/system/health"),
  openDirectory: (type: "dataset" | "job", id: string) => request<{ opened: true }>(
    "/api/system/open-directory",
    { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ type, id }) }
  ),
  dataset: (id: string) => request<DatasetResponse>(`/api/datasets/${id}`),
  deleteDataset: (id: string) => request<void>(`/api/datasets/${id}`, { method: "DELETE" }),
  mapHeight: (id: string, config: ModelingConfig) => request<DatasetResponse>(
    `/api/datasets/${id}/height-mapping`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        fieldName: config.heightField,
        unit: config.heightUnit,
        invalidPolicy: config.invalidPolicy,
        maximumHeightMeters: config.maximumHeightMeters
      })
    }
  ),
  createPreview: (datasetId: string, config: ModelingConfig, signal?: AbortSignal) => request<PreviewResponse>(
    "/api/model-previews",
    {
      method: "POST",
      signal,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(previewRequest(datasetId, config))
    }
  ),
  preview: (id: string) => request<PreviewResponse>(`/api/model-previews/${id}`),
  previewReport: (preview: PreviewResponse) => request<PreviewReport>(preview.links.report),
  deletePreview: (id: string) => request<void>(`/api/model-previews/${id}`, { method: "DELETE" }),
  createJob: (datasetId: string, config: ModelingConfig) => request<JobResponse>("/api/jobs", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      ...modelingRequest(datasetId, config),
      lods: [config.lod],
      zoom: config.zoom,
      workerCount: config.workerCount,
      outputFormats: config.outputFormats
    })
  }),
  job: (id: string) => request<JobResponse>(`/api/jobs/${id}`),
  cancelJob: (id: string) => request<JobResponse>(`/api/jobs/${id}`, { method: "DELETE" }),
  generationReport: (job: JobResponse) => request<GenerationReport>(job.links.report),
  generationManifest: (job: JobResponse) => request<GenerationManifest>(job.links.manifest)
};

function previewRequest(datasetId: string, config: ModelingConfig) {
  return {
    ...modelingRequest(datasetId, config),
    lod: config.lod,
    sampleSize: config.sampleSize
  };
}

function modelingRequest(datasetId: string, config: ModelingConfig) {
  return {
    datasetId,
    heightField: config.heightField,
    heightUnit: config.heightUnit,
    invalidPolicy: config.invalidPolicy,
    maximumHeightMeters: config.maximumHeightMeters,
    ruleVersion: config.ruleVersion,
    roofMode: config.roofMode,
    stylePreset: config.stylePreset,
    floorHeightMeters: config.floorHeightMeters,
    roofHeightRatio: config.roofHeightRatio,
    minimumRoofHeightMeters: config.minimumRoofHeightMeters,
    maximumRoofHeightMeters: config.maximumRoofHeightMeters,
    minimumBodyHeightMeters: config.minimumBodyHeightMeters,
    minimumPitchedBuildingHeightMeters: config.minimumPitchedBuildingHeightMeters,
    maximumPitchedBuildingHeightMeters: config.maximumPitchedBuildingHeightMeters,
    variantSeed: config.variantSeed
  };
}
