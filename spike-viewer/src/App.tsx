import { App as AntApp, Alert, Button, ConfigProvider, Layout, Spin, Steps, Tag, Typography } from "antd";
import { useEffect, useReducer, useState } from "react";
import { api, ApiClientError } from "./api/client";
import type { WizardStep } from "./domain";
import { guardStep, loadSession, maxAllowedStep, pathForStep, saveSession, sessionReducer, stepForPath } from "./state/session";
import { ConfigStep } from "./steps/ConfigStep";
import { GenerateStep } from "./steps/GenerateStep";
import { ImportStep } from "./steps/ImportStep";
import { PreviewStep } from "./steps/PreviewStep";

const stepItems = [
  { title: "导入数据", content: "GeoJSON / SHP" },
  { title: "配置模型", content: "高度与样式" },
  { title: "样例预览", content: "抽样复核" },
  { title: "生成交付", content: "3D Tiles" }
];

export default function App() {
  return (
    <ConfigProvider theme={{
      token: {
        colorPrimary: "#08736f", colorInfo: "#08736f", colorSuccess: "#168765",
        colorTextSecondary: "#536a72", colorTextTertiary: "#596f76", colorTextQuaternary: "#60757c",
        borderRadius: 10, fontFamily: "Inter, 'Segoe UI', 'Microsoft YaHei', sans-serif"
      },
      components: { Layout: { headerBg: "#071722" }, Button: { controlHeightLG: 44 } }
    }}>
      <AntApp><Workspace /></AntApp>
    </ConfigProvider>
  );
}

function Workspace() {
  const [session, dispatch] = useReducer(sessionReducer, undefined, loadSession);
  const [restoring, setRestoring] = useState(true);
  const [notice, setNotice] = useState("");

  useEffect(() => saveSession(session), [session]);
  useEffect(() => {
    const onPopState = () => dispatch({ type: "SET_STEP", step: stepForPath(window.location.pathname) });
    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, []);
  useEffect(() => {
    if (restoring) return;
    const expected = pathForStep(session.step);
    if (window.location.pathname !== expected) window.history.replaceState({}, "", expected);
  }, [session.step, restoring]);

  useEffect(() => {
    let active = true;
    const restore = async () => {
      if (!session.dataset) { setRestoring(false); return; }
      try {
        const latestDataset = await api.dataset(session.dataset.datasetId);
        if (!active) return;
        dispatch({ type: "SET_DATASET", dataset: latestDataset });
        if (session.preview && !session.job) {
          try {
            const latestPreview = await api.preview(session.preview.id);
            if (active) dispatch({ type: "SET_PREVIEW", preview: latestPreview });
          } catch {
            if (active) {
              dispatch({ type: "CLEAR_PREVIEW" });
              setNotice("上次样例已过期，配置仍保留，请重新生成样例。");
            }
          }
        }
        if (session.job) {
          try {
            const latestJob = await api.job(session.job.id);
            if (active) dispatch({ type: "SET_JOB", job: latestJob });
          } catch {
            if (active) {
              dispatch({ type: "RESET_JOB" });
              setNotice("上次全量任务已失效，请从样例页重新发起。");
            }
          }
        }
      } catch (failure) {
        if (active) {
          dispatch({ type: "RESET" });
          setNotice(failure instanceof ApiClientError && failure.status === 404
            ? "上次数据集已过期，请重新导入源文件。"
            : "无法恢复上次会话，已回到导入页。请确认本地服务正在运行。");
        }
      } finally {
        if (active) setRestoring(false);
      }
    };
    void restore();
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (restoring) return;
    const pathnameStep = window.location.pathname === "/" ? session.step : stepForPath(window.location.pathname);
    const allowed = guardStep(pathnameStep, session);
    if (allowed !== session.step) dispatch({ type: "SET_STEP", step: allowed });
  }, [restoring]);

  const navigate = (step: WizardStep, push = true) => {
    const allowed = guardStep(step, session);
    dispatch({ type: "SET_STEP", step: allowed });
    const path = pathForStep(allowed);
    if (push && window.location.pathname !== path) window.history.pushState({}, "", path);
  };
  const clearDataset = () => {
    const id = session.dataset?.datasetId;
    dispatch({ type: "RESET" });
    window.history.pushState({}, "", pathForStep(0));
    if (id) void api.deleteDataset(id).catch(() => undefined);
  };

  const allowedStep = maxAllowedStep(session);
  return (
    <Layout className="app-layout">
      <header className="app-header">
        <div className="brand-mark" aria-hidden="true"><span /><span /><span /></div>
        <div className="brand-copy"><strong>Vector2World</strong><span>建筑矢量到 3D Tiles</span></div>
        <Tag className="local-tag">LOCAL WORKFLOW</Tag>
      </header>
      <main>
        <section className="hero-strip">
          <div><div className="eyebrow light">OSM2WORLD PIPELINE</div><Typography.Title level={1}>从二维建筑面，生成可验证的三维城市成果</Typography.Title><Typography.Paragraph>可恢复的四步工作流：检查数据、配置规则、抽样预览、全量交付。</Typography.Paragraph></div>
          <div className="hero-orbit" aria-hidden="true"><i /><i /><i /></div>
        </section>
        <section className="stepper-shell" aria-label="建模步骤">
          <Steps current={session.step} items={stepItems.map((item, index) => ({ ...item, disabled: index > allowedStep }))} onChange={(index) => navigate(index as WizardStep)} responsive />
        </section>
        {notice && <Alert className="global-notice" type="warning" showIcon title={notice} closable onClose={() => setNotice("")} />}
        {restoring ? (
          <div className="restoring"><Spin size="large" /><strong>正在恢复工作会话…</strong><span>验证数据集、样例和任务状态</span></div>
        ) : (
          <div className="workspace">
            {session.step === 0 && <ImportStep dataset={session.dataset} onDataset={(dataset) => dispatch({ type: "SET_DATASET", dataset })} onClear={clearDataset} onNext={() => navigate(1)} />}
            {session.step === 1 && session.dataset && <ConfigStep dataset={session.dataset} config={session.config} onConfig={(config) => dispatch({ type: "UPDATE_CONFIG", config })} onDataset={(dataset) => dispatch({ type: "SET_DATASET", dataset })} onBack={() => navigate(0)} onNext={() => navigate(2)} />}
            {session.step === 2 && session.dataset && <PreviewStep dataset={session.dataset} config={session.config} preview={session.preview} onPreview={(preview) => dispatch({ type: "SET_PREVIEW", preview })} onBack={() => navigate(1)} onNext={() => navigate(3)} />}
            {session.step === 3 && session.dataset && <GenerateStep dataset={session.dataset} config={session.config} job={session.job} onJob={(job) => dispatch({ type: "SET_JOB", job })} onEvent={(event) => dispatch({ type: "APPLY_JOB_EVENT", event })} onResetJob={() => dispatch({ type: "RESET_JOB" })} onBack={() => navigate(2)} />}
          </div>
        )}
      </main>
      <footer><span>Vector2World · 本地优先的 OSM2World 建模工作台</span><Button type="link" onClick={() => { dispatch({ type: "RESET" }); window.history.pushState({}, "", "/import"); }}>清除会话状态</Button></footer>
    </Layout>
  );
}
