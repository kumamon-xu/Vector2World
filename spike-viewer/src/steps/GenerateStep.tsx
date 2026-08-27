import { CheckCircleOutlined, CloudDownloadOutlined, CloseCircleOutlined, FolderOpenOutlined, StopOutlined } from "@ant-design/icons";
import { Alert, Button, Descriptions, Popconfirm, Progress, Space, Table, Tag, Typography } from "antd";
import { useEffect, useMemo, useState } from "react";
import { api } from "../api/client";
import { CesiumViewport } from "../components/CesiumViewport";
import { formatBytes, MetricCard } from "../components/MetricCard";
import { WarningsPanel } from "../components/WarningsPanel";
import { isTerminal } from "../state/session";
import type { DatasetResponse, GenerationManifest, GenerationReport, JobEvent, JobResponse, ModelingConfig } from "../domain";

interface Props {
  dataset: DatasetResponse;
  config: ModelingConfig;
  job: JobResponse | null;
  onJob: (job: JobResponse) => void;
  onEvent: (event: JobEvent) => void;
  onResetJob: () => void;
  onBack: () => void;
}

const STATE_LABELS: Record<string, string> = {
  CREATED: "已排队", VALIDATING: "校验输入", PREPARING: "规划切片", TILING: "切片中",
  MODELING: "建模中", BUILDING_TILESET: "组装 Tileset", VALIDATING_RESULT: "验证成果",
  COMPLETED: "已完成", COMPLETED_WITH_WARNINGS: "完成（有警告）", FAILED: "失败", CANCELLED: "已取消"
};

export function GenerateStep({ dataset, config, job, onJob, onEvent, onResetJob, onBack }: Props) {
  const [starting, setStarting] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [streamState, setStreamState] = useState<"idle" | "live" | "reconnecting">("idle");
  const [error, setError] = useState("");
  const [report, setReport] = useState<GenerationReport | null>(null);
  const [manifest, setManifest] = useState<GenerationManifest | null>(null);
  const [mapReady, setMapReady] = useState(false);
  const [openingDirectory, setOpeningDirectory] = useState(false);

  const completed = job?.state === "COMPLETED" || job?.state === "COMPLETED_WITH_WARNINGS";
  useEffect(() => {
    if (!job || isTerminal(job.state)) { setStreamState("idle"); return; }
    const stream = new EventSource(job.links.events);
    stream.onopen = () => setStreamState("live");
    stream.onerror = () => setStreamState("reconnecting");
    stream.addEventListener("job", (raw) => {
      const event = JSON.parse((raw as MessageEvent<string>).data) as JobEvent;
      onEvent(event);
      if (isTerminal(event.state)) {
        void api.job(job.id).then(onJob).catch((failure) => setError(failure instanceof Error ? failure.message : String(failure)));
      }
    });
    return () => stream.close();
  }, [job?.id]);

  useEffect(() => {
    if (!job || !completed) { setReport(null); setManifest(null); return; }
    let active = true;
    Promise.all([api.generationReport(job), api.generationManifest(job)])
      .then(([nextReport, nextManifest]) => { if (active) { setReport(nextReport); setManifest(nextManifest); } })
      .catch((failure) => { if (active) setError(failure instanceof Error ? failure.message : String(failure)); });
    return () => { active = false; };
  }, [job?.id, completed]);

  const start = async () => {
    setStarting(true); setError(""); setMapReady(false);
    try { onJob(await api.createJob(dataset.datasetId, config)); }
    catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { setStarting(false); }
  };
  const cancel = async () => {
    if (!job) return;
    setCancelling(true); setError("");
    try { onJob(await api.cancelJob(job.id)); }
    catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { setCancelling(false); }
  };
  const openDirectory = async () => {
    if (!job) return;
    setOpeningDirectory(true); setError("");
    try { await api.openDirectory("job", job.id); }
    catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { setOpeningDirectory(false); }
  };

  const warnings = useMemo(() => job ? [
    ...job.warnings,
    ...job.tileFailures.map((failure) => `${failure.tile} · ${failure.category}（尝试 ${failure.attempts} 次）：${failure.message}`)
  ] : [], [job]);
  const validationPassed = report?.validation.valid === true;
  const downloadReady = Boolean(job && completed && validationPassed && mapReady);

  return (
    <div className="step-layout">
      <section className="content-card" aria-labelledby="generate-title">
        <div className="section-heading">
          <div><div className="eyebrow">STEP 04 · 全量生产</div><Typography.Title id="generate-title" level={2}>生成并交付 3D Tiles</Typography.Title></div>
          {completed ? <CheckCircleOutlined className="section-icon success-icon" /> : job?.state === "FAILED" ? <CloseCircleOutlined className="section-icon danger-icon" /> : null}
        </div>
        {!job ? (
          <div className="launch-panel">
            <div><strong>准备处理 {dataset.validGeometryCount.toLocaleString("zh-CN")} 个有效建筑面</strong><span>Zoom {config.zoom} · LOD {config.lod} · {config.workerCount} 个并行任务 · 输出 3D Tiles</span></div>
            <Button type="primary" size="large" loading={starting} onClick={() => void start()} data-testid="start-job">开始全量生成</Button>
          </div>
        ) : (
          <div className="job-progress" data-testid="job-progress">
            <div className="progress-title"><div><Tag color={completed ? "green" : job.state === "FAILED" ? "red" : job.state === "CANCELLED" ? "default" : "processing"}>{STATE_LABELS[job.state] || job.state}</Tag><code>{job.id}</code></div><span>{streamState === "live" ? "实时连接" : streamState === "reconnecting" ? "正在重连…" : isTerminal(job.state) ? "任务已结束" : "等待事件"}</span></div>
            <Progress percent={Math.round(job.progress * 100)} status={job.state === "FAILED" ? "exception" : completed ? "success" : "active"} />
            <div className="progress-meta"><span>{job.completedTiles.toLocaleString("zh-CN")} / {job.totalTiles.toLocaleString("zh-CN")} 切片</span><span>最后更新 {new Date(job.updatedAt).toLocaleTimeString("zh-CN")}</span></div>
            {!isTerminal(job.state) && (
              <Popconfirm title="确认取消这个生成任务？" description="已完成的中间切片不会作为成果交付。" okText="确认取消" cancelText="继续运行" onConfirm={() => void cancel()}>
                <Button danger icon={<StopOutlined />} loading={cancelling} data-testid="cancel-job">取消任务</Button>
              </Popconfirm>
            )}
            {(job.state === "FAILED" || job.state === "CANCELLED") && <Alert className="block-alert" type={job.state === "FAILED" ? "error" : "warning"} showIcon title={job.state === "FAILED" ? "生成失败" : "任务已取消"} description={job.error || "可以保留当前配置并重新运行。"} action={<Button onClick={onResetJob}>重新运行</Button>} />}
          </div>
        )}
        {error && <Alert className="block-alert" type="error" showIcon title="任务通信失败" description={error} closable onClose={() => setError("")} />}
      </section>

      {job && completed && report && manifest && (
        <>
          <section className="content-card" aria-labelledby="result-map-title">
            <div className="section-heading"><div><Typography.Title id="result-map-title" level={3}>最终成果</Typography.Title><Typography.Text type="secondary">仅在生成报告通过结构验证后加载。</Typography.Text></div><Tag color={validationPassed && mapReady ? "green" : "processing"}>{validationPassed ? (mapReady ? "成果可交付" : "正在加载成果") : "验证未通过"}</Tag></div>
            {validationPassed ? (
              <CesiumViewport source={{ kind: "tileset", url: job.links.tileset, bounds: manifest.boundsWgs84 }} label="最终 3D Tiles 成果" height={500} onReadyChange={setMapReady} />
            ) : <Alert type="error" showIcon title="成果结构验证未通过" description={report.validation.errors.join("；") || "请查看生成报告。"} />}
          </section>

          <section className="content-card" aria-labelledby="report-title" data-testid="generation-report">
            <div className="section-heading"><Typography.Title id="report-title" level={3}>生成报告</Typography.Title><Tag color={report.validation.valid ? "green" : "red"}>3D Tiles {report.validation.valid ? "验证通过" : "验证失败"}</Tag></div>
            <div className="metric-grid four">
              <MetricCard label="建模建筑" value={report.modeledBuildings.toLocaleString("zh-CN")} />
              <MetricCard label="成功切片" value={report.successfulTiles.toLocaleString("zh-CN")} detail={`失败 ${report.failedTiles}`} />
              <MetricCard label="三角形" value={report.triangleCount.toLocaleString("zh-CN")} />
              <MetricCard label="成果体积" value={formatBytes(report.outputBytes)} />
            </div>
            <Descriptions size="small" column={{ xs: 1, md: 2, xl: 3 }}>
              <Descriptions.Item label="耗时">{(report.elapsedMillis / 1000).toFixed(2)} 秒</Descriptions.Item>
              <Descriptions.Item label="跨切片建筑">{report.crossTileBuildings.toLocaleString("zh-CN")}</Descriptions.Item>
              <Descriptions.Item label="大型建筑">{report.largeBuildings.toLocaleString("zh-CN")}</Descriptions.Item>
              <Descriptions.Item label="Tileset / GLB">{report.validation.tilesetCount} / {report.validation.glbCount}</Descriptions.Item>
              <Descriptions.Item label="规则版本">{manifest.ruleVersion}</Descriptions.Item>
              <Descriptions.Item label="所有权哈希"><code>{report.ownershipHash}</code></Descriptions.Item>
            </Descriptions>
            <WarningsPanel warnings={warnings} title="全量警告与失败切片" />
          </section>

          <section className="content-card" aria-labelledby="delivery-title">
            <div className="section-heading"><div><Typography.Title id="delivery-title" level={3}>成果交付</Typography.Title><Typography.Text type="secondary">可下载 ZIP；Windows 本地版还可通过受控接口打开本任务成果目录。</Typography.Text></div><Tag>本地安全桥接</Tag></div>
            <Table
              size="small"
              rowKey="relativePath"
              pagination={false}
              dataSource={job.artifacts}
              columns={[{ title: "成果", dataIndex: "name" }, { title: "相对路径", dataIndex: "relativePath", responsive: ["md"] }, { title: "类型", dataIndex: "mediaType", responsive: ["lg"] }, { title: "大小", dataIndex: "bytes", render: (value: number) => formatBytes(value) }]}
            />
            <div className="delivery-actions">
              <span>{downloadReady ? "空间内容与报告均已验证，可以下载。" : "等待成果在 Cesium 中成功加载后开放下载。"}</span>
              <Space wrap>
                <Button size="large" icon={<FolderOpenOutlined />} loading={openingDirectory} onClick={() => void openDirectory()} data-testid="open-result-directory">打开成果目录</Button>
                <Button type="primary" size="large" icon={<CloudDownloadOutlined />} disabled={!downloadReady} href={job.links.download} data-testid="download-result">下载完整 ZIP</Button>
              </Space>
            </div>
          </section>
        </>
      )}
      <div className="step-actions"><Button onClick={onBack}>返回样例</Button>{job && isTerminal(job.state) ? <Button onClick={onResetJob}>使用当前配置重新生成</Button> : <span />}</div>
    </div>
  );
}
