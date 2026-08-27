export type WizardStep = 0 | 1 | 2 | 3;

export const STEP_PATHS = ["/import", "/configure", "/preview", "/generate"] as const;

export const TERMINAL_JOB_STATES = [
  "COMPLETED",
  "COMPLETED_WITH_WARNINGS",
  "FAILED",
  "CANCELLED"
] as const;

export type JobState =
  | "CREATED"
  | "VALIDATING"
  | "PREPARING"
  | "TILING"
  | "MODELING"
  | "BUILDING_TILESET"
  | "VALIDATING_RESULT"
  | (typeof TERMINAL_JOB_STATES)[number];

export interface FieldMetadata {
  name: string;
  type: string;
  presentCount: number;
  nullOrEmptyCount: number;
  numericCount: number;
  sampleValues: string[];
}

export interface HeightQuality {
  valid: number;
  nullOrEmpty: number;
  nonNumeric: number;
  nonFinite: number;
  nonPositive: number;
  aboveMaximum: number;
  minimumMeters: number | null;
  maximumMeters: number | null;
  averageMeters: number | null;
}

export interface DatasetIssue {
  severity: "WARNING" | "ERROR";
  code: string;
  message: string;
  count: number;
}

export interface DatasetResponse {
  schemaVersion: string;
  datasetId: string;
  status: string;
  createdAt: string;
  format: "GEOJSON" | "SHP";
  crs: string;
  sourceEncoding: string | null;
  layers: Array<{ name: string; geometryType: string; selected: boolean }>;
  geometryTypes: Record<string, number>;
  featureCount: number;
  validGeometryCount: number;
  skippedInvalidGeometry: number;
  repairedGeometryCount: number;
  bboxWgs84: number[];
  fields: FieldMetadata[];
  heightCandidates: Array<{ fieldName: string; score: number; qualityAssumingMeters: HeightQuality }>;
  heightMapping: HeightMapping | null;
  heightQuality: HeightQuality | null;
  issues: DatasetIssue[];
}

export interface HeightMapping {
  fieldName: string;
  unit: "M" | "CM" | "MM" | "FT" | "m" | "cm" | "mm" | "ft";
  invalidPolicy: "SKIP" | "FAIL";
  maximumHeightMeters: number;
}

export interface ModelingConfig {
  heightField: string;
  heightUnit: "m" | "cm" | "mm" | "ft";
  invalidPolicy: "SKIP" | "FAIL";
  maximumHeightMeters: number;
  ruleVersion: "m2-rules-v1";
  roofMode: "CONSERVATIVE" | "AUTO_SIMPLE" | "FLAT_FACADE_DETAIL";
  stylePreset: "neutral-city" | "warm-residential" | "modern-city" | "industrial";
  floorHeightMeters: number;
  roofHeightRatio: number;
  minimumRoofHeightMeters: number;
  maximumRoofHeightMeters: number;
  minimumBodyHeightMeters: number;
  minimumPitchedBuildingHeightMeters: number;
  maximumPitchedBuildingHeightMeters: number;
  lod: 2;
  sampleSize: number;
  variantSeed: number;
  zoom: number;
  workerCount: number;
  outputFormats: ["3DTILES"];
}

export const DEFAULT_CONFIG: ModelingConfig = {
  heightField: "",
  heightUnit: "m",
  invalidPolicy: "SKIP",
  maximumHeightMeters: 10_000,
  ruleVersion: "m2-rules-v1",
  roofMode: "AUTO_SIMPLE",
  stylePreset: "neutral-city",
  floorHeightMeters: 3.2,
  roofHeightRatio: 0.15,
  minimumRoofHeightMeters: 0.8,
  maximumRoofHeightMeters: 3,
  minimumBodyHeightMeters: 2.5,
  minimumPitchedBuildingHeightMeters: 6,
  maximumPitchedBuildingHeightMeters: 30,
  lod: 2,
  sampleSize: 100,
  variantSeed: 1_446_139_724,
  zoom: 15,
  workerCount: Math.max(1, Math.min(8,
    ((typeof navigator === "undefined" ? 4 : navigator.hardwareConcurrency) || 4) - 1)),
  outputFormats: ["3DTILES"]
};

export interface ModelingConfigView {
  ruleVersion: string;
  roofMode: string;
  stylePreset: string;
  floorHeightMeters: number;
  lod: number;
  sampleSize: number;
  configHash: string;
}

export interface PreviewResponse {
  schemaVersion: string;
  id: string;
  datasetId: string;
  status: "GENERATING" | "READY" | "FAILED" | "DELETING" | "DELETED";
  createdAt: string;
  expiresAt: string;
  disclaimer: string;
  heightMapping: Record<string, unknown>;
  config: ModelingConfigView;
  selectedBuildings: number;
  modeledBuildings: number;
  meshCount: number;
  selectionHash: string | null;
  ruleOutputHash: string | null;
  boundsWgs84: number[];
  bucketCoverage: Record<string, string[]>;
  warnings: string[];
  featureFailures: Array<{ featureId: string; category: string; message: string }>;
  links: { self: string; tileset: string; report: string };
}

export interface JobArtifact {
  name: string;
  relativePath: string;
  mediaType: string;
  bytes: number;
}

export interface TileFailure {
  tile: string;
  category: string;
  attempts: number;
  retryable: boolean;
  message: string;
}

export interface JobResponse {
  schemaVersion: string;
  id: string;
  datasetId: string;
  state: JobState;
  createdAt: string;
  updatedAt: string;
  expiresAt: string;
  completedTiles: number;
  totalTiles: number;
  progress: number;
  error: string | null;
  modelingConfig: ModelingConfigView;
  tilingConfig: Record<string, unknown>;
  successfulTiles: number | null;
  failedTiles: number | null;
  modeledBuildings: number | null;
  outputBytes: number | null;
  ownershipHash: string | null;
  warnings: string[];
  tileFailures: TileFailure[];
  artifacts: JobArtifact[];
  links: {
    self: string;
    events: string;
    tileset: string;
    manifest: string;
    report: string;
    download: string;
  };
}

export interface JobEvent {
  id: number;
  timestamp: string;
  state: JobState;
  completedTiles: number;
  totalTiles: number;
  message: string;
}

export interface GenerationManifest {
  schemaVersion: string;
  applicationVersion: string;
  osm2worldVersion: string;
  osm2worldCommit: string;
  ruleVersion: string;
  presetVersion: string;
  configHash: string;
  sourceFormat: string;
  sourceCrs: string;
  sourceEncoding?: string;
  zoom: number;
  lods: number[];
  outputFormats: string[];
  crossTileStrategy: string;
  boundsWgs84: number[];
  ownershipHash: string;
  tileContents: Array<{ tile: string; lod: number; tilesetPath: string; glbPath: string }>;
  buildTime: string;
}

export interface GenerationReport {
  schemaVersion: string;
  state: "COMPLETED" | "COMPLETED_WITH_WARNINGS";
  elapsedMillis: number;
  inputFeatures: number;
  validBuildings: number;
  plannedTiles: number;
  successfulTiles: number;
  failedTiles: number;
  modeledBuildings: number;
  meshCount: number;
  vertexCount: number;
  triangleCount: number;
  crossTileBuildings: number;
  largeBuildings: number;
  outputBytes: number;
  quantization: string;
  ownershipHash: string;
  warnings: string[];
  tileFailures: TileFailure[];
  tileResults: Array<{ tile: string; modeledBuildings: number; outputBytes: number }>;
  validation: { valid: boolean; assetVersion: string; tilesetCount: number; glbCount: number; errors: string[] };
}

export interface PreviewReport {
  focusBoundsWgs84?: number[];
  validation: { valid: boolean; assetVersion: string; glbCount: number; errors: string[] };
}

export interface ApiErrorBody {
  status?: number;
  code?: string;
  message?: string;
  details?: Record<string, unknown>;
}
