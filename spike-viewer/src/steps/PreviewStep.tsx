import { ExperimentOutlined, SyncOutlined } from "@ant-design/icons";
import { Alert, Button, Descriptions, Space, Tag, Typography } from "antd";
import { useEffect, useRef, useState } from "react";
import { api } from "../api/client";
import { CesiumViewport } from "../components/CesiumViewport";
import { MetricCard } from "../components/MetricCard";
import { WarningsPanel } from "../components/WarningsPanel";
import type { DatasetResponse, ModelingConfig, PreviewReport, PreviewResponse } from "../domain";

interface Props {
  dataset: DatasetResponse;
  config: ModelingConfig;
  preview: PreviewResponse | null;
  onPreview: (preview: PreviewResponse) => void;
  onBack: () => void;
  onNext: () => void;
}

export function PreviewStep({ dataset, config, preview, onPreview, onBack, onNext }: Props) {
  const controller = useRef<AbortController | null>(null);
  const sequence = useRef(0);
  const [generating, setGenerating] = useState(false);
  const [mapReady, setMapReady] = useState(false);
  const [error, setError] = useState("");
  const [report, setReport] = useState<PreviewReport | null>(null);

  useEffect(() => {
    if (!preview || preview.status !== "READY") { setReport(null); return; }
    let active = true;
    api.previewReport(preview).then((value) => { if (active) setReport(value); }).catch(() => { if (active) setReport(null); });
    return () => { active = false; };
  }, [preview?.id]);

  useEffect(() => () => controller.current?.abort(), []);

  const create = async () => {
    controller.current?.abort();
    const current = ++sequence.current;
    const nextController = new AbortController();
    controller.current = nextController;
    setGenerating(true);
    setError("");
    setMapReady(false);
    try {
      const created = await api.createPreview(dataset.datasetId, config, nextController.signal);
      if (current === sequence.current) onPreview(created);
    } catch (failure) {
      if (!(failure instanceof DOMException && failure.name === "AbortError") && current === sequence.current) {
        setError(failure instanceof Error ? failure.message : String(failure));
      }
    } finally {
      if (current === sequence.current) setGenerating(false);
    }
  };

  const warnings = preview ? [
    ...preview.warnings,
    ...preview.featureFailures.map((failure) => `${failure.featureId} · ${failure.category}: ${failure.message}`)
  ] : [];

  return (
    <div className="step-layout">
      <section className="content-card" aria-labelledby="preview-title">
        <div className="section-heading">
          <div><div className="eyebrow">STEP 03 · 抽样验证</div><Typography.Title id="preview-title" level={2}>生成可重复的建模样例</Typography.Title></div>
          <ExperimentOutlined className="section-icon" />
        </div>
        <Alert type="info" showIcon title="样例只用于视觉与规则复核" description="样例按空间分桶稳定抽取，不代表最终成果的完整数量、性能或错误分布。最终任务会重新处理全部有效建筑。" />
        <div className="preview-command">
          <div><strong>{preview ? `样例 ${preview.id.slice(0, 8)}` : "尚未生成样例"}</strong><span>{preview ? `状态：${preview.status}` : `将抽取至多 ${config.sampleSize} 栋建筑`}</span></div>
          <Button type="primary" size="large" icon={preview ? <SyncOutlined /> : <ExperimentOutlined />} loading={generating} onClick={() => void create()} data-testid="create-preview">{preview ? "重新生成" : "生成样例"}</Button>
        </div>
        {error && <Alert className="block-alert" type="error" showIcon title="样例生成失败" description={error} action={<Button onClick={() => void create()}>重试</Button>} />}
      </section>

      {preview?.status === "READY" && (
        <>
          <section className="content-card" aria-labelledby="preview-map-title">
            <div className="section-heading"><div><Typography.Title id="preview-map-title" level={3}>3D 样例</Typography.Title><Typography.Text type="secondary">先确认体量、屋顶与整体风格，再运行全量任务。</Typography.Text></div><Tag color={mapReady ? "green" : "processing"}>{mapReady ? "内容已加载" : "正在验证内容"}</Tag></div>
            <CesiumViewport
              source={{ kind: "tileset", url: preview.links.tileset, bounds: preview.boundsWgs84, focusBounds: report?.focusBoundsWgs84 }}
              label="抽样建筑三维预览"
              onReadyChange={setMapReady}
            />
          </section>
          <section className="content-card" aria-labelledby="preview-stats-title">
            <Typography.Title id="preview-stats-title" level={3}>样例统计</Typography.Title>
            <div className="metric-grid">
              <MetricCard label="抽中建筑" value={preview.selectedBuildings.toLocaleString("zh-CN")} />
              <MetricCard label="完成建模" value={preview.modeledBuildings.toLocaleString("zh-CN")} />
              <MetricCard label="网格数量" value={preview.meshCount.toLocaleString("zh-CN")} />
            </div>
            <Descriptions size="small" column={{ xs: 1, md: 2 }}>
              <Descriptions.Item label="选择哈希"><code>{preview.selectionHash || "—"}</code></Descriptions.Item>
              <Descriptions.Item label="规则输出哈希"><code>{preview.ruleOutputHash || "—"}</code></Descriptions.Item>
              <Descriptions.Item label="配置哈希"><code>{preview.config.configHash}</code></Descriptions.Item>
              <Descriptions.Item label="验证状态">{report ? (report.validation.valid ? "通过" : "失败") : "读取中"}</Descriptions.Item>
            </Descriptions>
            <WarningsPanel warnings={warnings} title="样例警告与失败要素" />
          </section>
          <div className="step-actions"><Button onClick={onBack}>返回配置</Button><Space><Button onClick={() => void create()}>调整后重生成</Button><Button type="primary" size="large" disabled={!mapReady || report?.validation.valid === false} onClick={onNext} data-testid="preview-next">运行全量生成</Button></Space></div>
        </>
      )}
      {!preview && <div className="step-actions"><Button onClick={onBack}>返回配置</Button><span /></div>}
    </div>
  );
}
