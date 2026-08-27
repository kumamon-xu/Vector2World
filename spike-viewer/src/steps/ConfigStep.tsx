import { SettingOutlined } from "@ant-design/icons";
import { Alert, Button, Collapse, Form, InputNumber, Radio, Select, Slider, Tag, Typography } from "antd";
import { useMemo, useState } from "react";
import { api } from "../api/client";
import { MetricCard } from "../components/MetricCard";
import type { DatasetResponse, ModelingConfig } from "../domain";

interface Props {
  dataset: DatasetResponse;
  config: ModelingConfig;
  onConfig: (config: ModelingConfig) => void;
  onDataset: (dataset: DatasetResponse) => void;
  onBack: () => void;
  onNext: () => void;
}

export function ConfigStep({ dataset, config, onConfig, onDataset, onBack, onNext }: Props) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const field = dataset.fields.find((candidate) => candidate.name === config.heightField);
  const estimate = useMemo(() => {
    if (dataset.heightMapping?.fieldName === config.heightField && dataset.heightQuality) {
      const quality = dataset.heightQuality;
      const invalid = quality.nullOrEmpty + quality.nonNumeric + quality.nonFinite + quality.nonPositive + quality.aboveMaximum;
      return { valid: quality.valid, invalid, exact: true };
    }
    return { valid: field?.numericCount ?? 0, invalid: dataset.featureCount - (field?.numericCount ?? 0), exact: false };
  }, [dataset, field, config.heightField]);

  const change = <K extends keyof ModelingConfig>(key: K, value: ModelingConfig[K]) => {
    onConfig({ ...config, [key]: value });
  };
  const valid = Boolean(config.heightField) && config.maximumHeightMeters > 0
    && config.floorHeightMeters > 0 && config.workerCount >= 1 && config.workerCount <= 8;

  const validateAndContinue = async () => {
    if (!valid) return;
    setSaving(true);
    setError("");
    try {
      const materialized = await api.mapHeight(dataset.datasetId, config);
      onDataset(materialized);
      onNext();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="step-layout">
      <section className="content-card" aria-labelledby="config-title">
        <div className="section-heading">
          <div><div className="eyebrow">STEP 02 · 建模规则</div><Typography.Title id="config-title" level={2}>配置高度与样式</Typography.Title></div>
          <SettingOutlined className="section-icon" />
        </div>
        <Typography.Paragraph type="secondary">高度将统一换算为米。所有参数会同时用于样例和最终生成，修改后旧结果会自动失效。</Typography.Paragraph>

        <Form layout="vertical" requiredMark="optional">
          <div className="form-grid three-columns">
            <Form.Item label="高度字段" required validateStatus={config.heightField ? "success" : "error"} help={config.heightField ? undefined : "请选择高度字段"}>
              <Select
                value={config.heightField || undefined}
                placeholder="选择字段"
                showSearch
                onChange={(value) => change("heightField", value)}
                options={dataset.fields.map((item) => ({ value: item.name, label: `${item.name} · ${item.type}` }))}
                data-testid="height-field"
              />
            </Form.Item>
            <Form.Item label="高度单位" required>
              <Select value={config.heightUnit} onChange={(value) => change("heightUnit", value)} options={[
                { value: "m", label: "米（m）" }, { value: "cm", label: "厘米（cm）" },
                { value: "mm", label: "毫米（mm）" }, { value: "ft", label: "英尺（ft）" }
              ]} data-testid="height-unit" />
            </Form.Item>
            <Form.Item label="异常高度策略" required>
              <Radio.Group value={config.invalidPolicy} onChange={(event) => change("invalidPolicy", event.target.value)}>
                <Radio.Button value="SKIP">跳过并报告</Radio.Button><Radio.Button value="FAIL">立即失败</Radio.Button>
              </Radio.Group>
            </Form.Item>
          </div>
          <div className="form-grid three-columns">
            <Form.Item label="最大允许高度（米）" required>
              <InputNumber min={0.1} max={100000} value={config.maximumHeightMeters} onChange={(value) => change("maximumHeightMeters", value ?? 10000)} style={{ width: "100%" }} />
            </Form.Item>
            <Form.Item label="建筑风格">
              <Select value={config.stylePreset} onChange={(value) => change("stylePreset", value)} options={[
                { value: "neutral-city", label: "中性城市" }, { value: "warm-residential", label: "暖色住宅" },
                { value: "modern-city", label: "现代城市" }, { value: "industrial", label: "工业建筑" }
              ]} />
            </Form.Item>
            <Form.Item label="屋顶模式">
              <Select value={config.roofMode} onChange={(value) => change("roofMode", value)} options={[
                { value: "CONSERVATIVE", label: "保守平顶" }, { value: "AUTO_SIMPLE", label: "自动简化" },
                { value: "FLAT_FACADE_DETAIL", label: "平顶立面细节" }
              ]} />
            </Form.Item>
          </div>
          <Form.Item label={`标准层高：${config.floorHeightMeters.toFixed(1)} 米`}>
            <Slider min={2.4} max={5} step={0.1} value={config.floorHeightMeters} onChange={(value) => change("floorHeightMeters", value)} />
          </Form.Item>

          <Collapse ghost items={[{
            key: "advanced",
            label: "高级建模与切片参数",
            children: (
              <div className="form-grid three-columns">
                <Form.Item label="屋顶高度比例"><InputNumber min={0} max={0.5} step={0.01} value={config.roofHeightRatio} onChange={(value) => change("roofHeightRatio", value ?? 0.15)} /></Form.Item>
                <Form.Item label="最小屋顶高度（米）"><InputNumber min={0} value={config.minimumRoofHeightMeters} onChange={(value) => change("minimumRoofHeightMeters", value ?? 0.8)} /></Form.Item>
                <Form.Item label="最大屋顶高度（米）"><InputNumber min={0} value={config.maximumRoofHeightMeters} onChange={(value) => change("maximumRoofHeightMeters", value ?? 3)} /></Form.Item>
                <Form.Item label="最小主体高度（米）"><InputNumber min={0} value={config.minimumBodyHeightMeters} onChange={(value) => change("minimumBodyHeightMeters", value ?? 2.5)} /></Form.Item>
                <Form.Item label="倾斜屋顶下限（米）"><InputNumber min={0} value={config.minimumPitchedBuildingHeightMeters} onChange={(value) => change("minimumPitchedBuildingHeightMeters", value ?? 6)} /></Form.Item>
                <Form.Item label="倾斜屋顶上限（米）"><InputNumber min={0} value={config.maximumPitchedBuildingHeightMeters} onChange={(value) => change("maximumPitchedBuildingHeightMeters", value ?? 30)} /></Form.Item>
                <Form.Item label="样例建筑数"><InputNumber min={1} max={500} value={config.sampleSize} onChange={(value) => change("sampleSize", value ?? 100)} /></Form.Item>
                <Form.Item label="3D Tiles Zoom"><InputNumber min={0} max={22} value={config.zoom} onChange={(value) => change("zoom", value ?? 15)} /></Form.Item>
                <Form.Item label="并行任务数（1–8）" validateStatus={config.workerCount >= 1 && config.workerCount <= 8 ? "success" : "error"}><InputNumber min={1} max={8} value={config.workerCount} onChange={(value) => change("workerCount", value ?? 1)} /></Form.Item>
              </div>
            )
          }]} />
        </Form>
      </section>

      <section className="content-card" aria-labelledby="estimate-title">
        <div className="section-heading"><Typography.Title id="estimate-title" level={3}>实时质量估算</Typography.Title><Tag color={estimate.exact ? "green" : "gold"}>{estimate.exact ? "已验证" : "字段估算"}</Tag></div>
        <div className="metric-grid">
          <MetricCard label="预计可建模" value={estimate.valid.toLocaleString("zh-CN")} detail="有限正数高度" />
          <MetricCard label="预计异常" value={Math.max(0, estimate.invalid).toLocaleString("zh-CN")} detail={config.invalidPolicy === "SKIP" ? "将跳过并写入报告" : "会阻止任务"} />
          <MetricCard label="输出格式" value="3D Tiles" detail="包含 manifest 与报告" />
        </div>
        {config.invalidPolicy === "FAIL" && estimate.invalid > 0 && <Alert className="block-alert" type="warning" showIcon title="当前策略会因异常高度中止" description="可改为“跳过并报告”，或先清理源数据。" />}
        {error && <Alert className="block-alert" type="error" showIcon title="高度校验失败" description={error} />}
      </section>

      <div className="step-actions"><Button onClick={onBack}>返回导入</Button><Button type="primary" size="large" loading={saving} disabled={!valid} onClick={() => void validateAndContinue()} data-testid="config-next">校验并生成样例</Button></div>
    </div>
  );
}
