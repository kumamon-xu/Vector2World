import { describe, expect, it } from "vitest";
import { DEFAULT_CONFIG, type DatasetResponse, type JobResponse } from "../domain";
import { applyJobEvent, configFingerprint, initialSession, maxAllowedStep, sessionReducer, warningPage } from "./session";

const dataset = { datasetId: "dataset-1", heightCandidates: [{ fieldName: "Elevation", score: 1, qualityAssumingMeters: {} }], fields: [] } as unknown as DatasetResponse;

describe("wizard session", () => {
  it("invalidates downstream output when modeling configuration changes", () => {
    let session = sessionReducer(initialSession(), { type: "SET_DATASET", dataset });
    expect(session.config.heightField).toBe("Elevation");
    session = { ...session, preview: { id: "preview-1" } as never, previewFingerprint: configFingerprint(session.config), job: { id: "job-1" } as never };
    const changed = sessionReducer(session, { type: "UPDATE_CONFIG", config: { ...session.config, floorHeightMeters: 3.5 } });
    expect(changed.preview).toBeNull();
    expect(changed.job).toBeNull();
  });

  it("never carries preview or job state into a different dataset", () => {
    const withOutput = {
      ...initialSession(), dataset, config: { ...DEFAULT_CONFIG, heightField: "Elevation" },
      preview: { id: "preview-1" } as never, job: { id: "job-1" } as never, lastEventId: 12
    };
    const replacement = { ...dataset, datasetId: "dataset-2" };
    const changed = sessionReducer(withOutput, { type: "SET_DATASET", dataset: replacement });
    expect(changed.preview).toBeNull();
    expect(changed.job).toBeNull();
    expect(changed.lastEventId).toBe(0);
    expect(changed.step).toBe(0);
  });

  it("keeps a restored job reachable even after preview expiry", () => {
    const session = { ...initialSession(), dataset, config: { ...DEFAULT_CONFIG, heightField: "Elevation" }, job: { datasetId: "dataset-1" } as JobResponse };
    expect(maxAllowedStep(session)).toBe(3);
  });

  it("ignores replayed SSE events and never regresses progress", () => {
    const job = { state: "MODELING", totalTiles: 10, completedTiles: 6, progress: 0.6 } as JobResponse;
    const duplicate = applyJobEvent(job, { id: 4, timestamp: "now", state: "TILING", totalTiles: 10, completedTiles: 2, message: "old" }, 4);
    expect(duplicate.job).toBe(job);
    const fresh = applyJobEvent(job, { id: 5, timestamp: "later", state: "TILING", totalTiles: 10, completedTiles: 4, message: "replay" }, 4);
    expect(fresh.job.state).toBe("MODELING");
    expect(fresh.job.completedTiles).toBe(6);
  });

  it("paginates large warning collections", () => {
    expect(warningPage(Array.from({ length: 101 }, (_, index) => String(index)), 3)).toEqual(["100"]);
  });
});
