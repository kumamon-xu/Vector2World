import {
  DEFAULT_CONFIG,
  STEP_PATHS,
  TERMINAL_JOB_STATES,
  type DatasetResponse,
  type JobEvent,
  type JobResponse,
  type ModelingConfig,
  type PreviewResponse,
  type WizardStep
} from "../domain";

export const SESSION_KEY = "vector2world.m4.session.v1";

export interface WizardSession {
  version: 1;
  step: WizardStep;
  dataset: DatasetResponse | null;
  config: ModelingConfig;
  preview: PreviewResponse | null;
  previewFingerprint: string | null;
  job: JobResponse | null;
  lastEventId: number;
  eventMessage: string;
}

export type SessionAction =
  | { type: "SET_STEP"; step: WizardStep }
  | { type: "SET_DATASET"; dataset: DatasetResponse }
  | { type: "CLEAR_DATASET" }
  | { type: "UPDATE_CONFIG"; config: ModelingConfig }
  | { type: "SET_PREVIEW"; preview: PreviewResponse }
  | { type: "CLEAR_PREVIEW" }
  | { type: "SET_JOB"; job: JobResponse }
  | { type: "APPLY_JOB_EVENT"; event: JobEvent }
  | { type: "RESET_JOB" }
  | { type: "RESET" };

export function initialSession(): WizardSession {
  return {
    version: 1,
    step: 0,
    dataset: null,
    config: { ...DEFAULT_CONFIG },
    preview: null,
    previewFingerprint: null,
    job: null,
    lastEventId: 0,
    eventMessage: ""
  };
}

export function loadSession(storage: Pick<Storage, "getItem"> = localStorage): WizardSession {
  try {
    const raw = storage.getItem(SESSION_KEY);
    if (!raw) return initialSession();
    const parsed = JSON.parse(raw) as Partial<WizardSession>;
    if (parsed.version !== 1) return initialSession();
    const hydrated: WizardSession = {
      ...initialSession(),
      ...parsed,
      config: { ...DEFAULT_CONFIG, ...parsed.config }
    };
    hydrated.step = guardStep(hydrated.step, hydrated);
    return hydrated;
  } catch {
    return initialSession();
  }
}

export function saveSession(session: WizardSession, storage: Pick<Storage, "setItem"> = localStorage) {
  storage.setItem(SESSION_KEY, JSON.stringify(session));
}

export function configFingerprint(config: ModelingConfig): string {
  return JSON.stringify(Object.entries(config).sort(([left], [right]) => left.localeCompare(right)));
}

export function maxAllowedStep(session: WizardSession): WizardStep {
  if (!session.dataset) return 0;
  if (!session.config.heightField) return 1;
  if (session.job?.datasetId === session.dataset.datasetId) return 3;
  if (!session.preview || session.preview.status !== "READY"
      || session.preview.datasetId !== session.dataset.datasetId
      || session.previewFingerprint !== configFingerprint(session.config)) return 2;
  return 3;
}

export function guardStep(requested: number, session: WizardSession): WizardStep {
  const bounded = Math.max(0, Math.min(3, requested)) as WizardStep;
  return Math.min(bounded, maxAllowedStep(session)) as WizardStep;
}

export function pathForStep(step: WizardStep): string {
  return STEP_PATHS[step];
}

export function stepForPath(pathname: string): WizardStep {
  const index = STEP_PATHS.indexOf(pathname as typeof STEP_PATHS[number]);
  return (index < 0 ? 0 : index) as WizardStep;
}

export function isTerminal(state: string): boolean {
  return TERMINAL_JOB_STATES.includes(state as typeof TERMINAL_JOB_STATES[number]);
}

const STATE_RANK: Record<string, number> = {
  CREATED: 0,
  VALIDATING: 1,
  PREPARING: 2,
  TILING: 3,
  MODELING: 4,
  BUILDING_TILESET: 5,
  VALIDATING_RESULT: 6,
  COMPLETED: 7,
  COMPLETED_WITH_WARNINGS: 7,
  FAILED: 7,
  CANCELLED: 7
};

export function applyJobEvent(
  job: JobResponse,
  event: JobEvent,
  lastEventId: number
): { job: JobResponse; lastEventId: number; message: string } {
  if (event.id <= lastEventId) return { job, lastEventId, message: "" };
  const state = (STATE_RANK[event.state] ?? -1) >= (STATE_RANK[job.state] ?? -1) ? event.state : job.state;
  const totalTiles = Math.max(job.totalTiles, event.totalTiles);
  const completedTiles = Math.min(totalTiles, Math.max(job.completedTiles, event.completedTiles));
  return {
    job: {
      ...job,
      state,
      totalTiles,
      completedTiles,
      progress: totalTiles === 0 ? job.progress : Math.max(job.progress, completedTiles / totalTiles),
      updatedAt: event.timestamp
    },
    lastEventId: event.id,
    message: event.message
  };
}

export function sessionReducer(session: WizardSession, action: SessionAction): WizardSession {
  switch (action.type) {
    case "SET_STEP":
      return { ...session, step: guardStep(action.step, session) };
    case "SET_DATASET": {
      const changed = session.dataset?.datasetId !== action.dataset.datasetId;
      const candidate = action.dataset.heightCandidates[0]?.fieldName ?? "";
      return {
        ...session,
        step: changed ? 0 : session.step,
        dataset: action.dataset,
        config: changed ? { ...DEFAULT_CONFIG, heightField: candidate } : session.config,
        preview: changed ? null : session.preview,
        previewFingerprint: changed ? null : session.previewFingerprint,
        job: changed ? null : session.job,
        lastEventId: changed ? 0 : session.lastEventId,
        eventMessage: ""
      };
    }
    case "CLEAR_DATASET":
      return initialSession();
    case "UPDATE_CONFIG": {
      const changed = configFingerprint(action.config) !== configFingerprint(session.config);
      return {
        ...session,
        config: action.config,
        preview: changed ? null : session.preview,
        previewFingerprint: changed ? null : session.previewFingerprint,
        job: changed ? null : session.job,
        lastEventId: changed ? 0 : session.lastEventId,
        eventMessage: ""
      };
    }
    case "SET_PREVIEW":
      return {
        ...session,
        preview: action.preview,
        previewFingerprint: configFingerprint(session.config),
        job: null,
        lastEventId: 0,
        eventMessage: ""
      };
    case "CLEAR_PREVIEW":
      return { ...session, preview: null, previewFingerprint: null, step: Math.min(session.step, 2) as WizardStep };
    case "SET_JOB":
      return { ...session, job: action.job, step: 3, eventMessage: action.job.state };
    case "APPLY_JOB_EVENT": {
      if (!session.job) return session;
      const applied = applyJobEvent(session.job, action.event, session.lastEventId);
      return { ...session, job: applied.job, lastEventId: applied.lastEventId,
        eventMessage: applied.message || session.eventMessage };
    }
    case "RESET_JOB":
      return { ...session, job: null, lastEventId: 0, eventMessage: "" };
    case "RESET":
      return initialSession();
  }
}

export function warningPage(warnings: string[], page: number, pageSize = 50): string[] {
  const safePage = Math.max(1, page);
  return warnings.slice((safePage - 1) * pageSize, safePage * pageSize);
}
