import { CloudUploadOutlined, DeleteOutlined, FileSearchOutlined } from "@ant-design/icons";
import { Alert, Button, Descriptions, Input, Progress, Space, Table, Tag, Typography } from "antd";
import { useRef, useState, type DragEvent } from "react";
import { uploadDataset } from "../api/client";
import { CesiumViewport } from "../components/CesiumViewport";
import type { DatasetResponse } from "../domain";

interface Props {
  dataset: DatasetResponse | null;
  onDataset: (dataset: DatasetResponse) => void;
  onClear: () => void;
  onNext: () => void;
}

export function ImportStep({ dataset, onDataset, onClear, onNext }: Props) {
  const input = useRef<HTMLInputElement>(null);
  const controller = useRef<AbortController | null>(null);
  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState(0);
  const [error, setError] = useState("");
  const [fileName, setFileName] = useState("");
  const [sourceCrs, setSourceCrs] = useState("");
  const [dbfCharset, setDbfCharset] = useState("");

  const beginUpload = async (file?: File) => {
    if (!file) return;
    const lower = file.name.toLowerCase();
    if (!(lower.endsWith(".geojson") || lower.endsWith(".json") || lower.endsWith(".zip"))) {
      setError("仅支持 GeoJSON（.geojson/.json）或包含完整 Shapefile 的 ZIP。");
      return;
    }
    controller.current?.abort();
    const nextController = new AbortController();
    controller.current = nextController;
    setUploading(true);
    setProgress(0);
    setError("");
    setFileName(file.name);
    try {
      const imported = await uploadDataset(
        file,
        { sourceCrs: sourceCrs.trim() || undefined, dbfCharset: dbfCharset.trim() || undefined },
        setProgress,
        nextController.signal
      );
      setProgress(100);
      onDataset(imported);
    } catch (failure) {
      if (failure instanceof DOMException && failure.name === "AbortError") {
        setError("上传已取消，可重新选择文件。");
      } else {
        setError(failure instanceof Error ? failure.message : String(failure));
      }
    } finally {
      if (controller.current === nextController) {
        controller.current = null;
        setUploading(false);
      }
    }
  };

  const handleDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    void beginUpload(event.dataTransfer.files[0]);
  };

  return (
    <div className="step-layout">
      <section className="content-card import-card" aria-labelledby="import-title">
        <div className="eyebrow">STEP 01 · 数据入口</div>
        <Typography.Title id="import-title" level={2}>导入建筑面矢量</Typography.Title>
        <Typography.Paragraph type="secondary">
          GeoJSON 可直接上传；Shapefile 请将 .shp、.shx、.dbf、.prj 打包为一个 ZIP。文件仅发送到当前 Vector2World 服务。
        </Typography.Paragraph>

        <input
          ref={input}
          className="visually-hidden"
          type="file"
          aria-label="选择建筑矢量文件"
          accept=".geojson,.json,.zip,application/geo+json,application/json,application/zip"
          onChange={(event) => { void beginUpload(event.target.files?.[0]); event.target.value = ""; }}
        />
        <div
          className={`upload-zone ${uploading ? "is-uploading" : ""}`}
          onDragOver={(event) => event.preventDefault()}
          onDrop={handleDrop}
          role="button"
          tabIndex={0}
          onKeyDown={(event) => { if (event.key === "Enter" || event.key === " ") input.current?.click(); }}
          onClick={() => !uploading && input.current?.click()}
          aria-label="选择或拖入建筑矢量文件"
          data-testid="upload-zone"
        >
          <CloudUploadOutlined className="upload-icon" />
          <strong>{uploading ? "正在上传并解析…" : "拖入文件，或点击选择"}</strong>
          <span>{fileName || "支持 GeoJSON / Shapefile ZIP"}</span>
        </div>

        <details className="native-details">
          <summary>导入高级选项</summary>
          <div className="form-grid two-columns">
            <label>源坐标系（缺少 .prj 时）<Input value={sourceCrs} onChange={(event) => setSourceCrs(event.target.value)} placeholder="例如 EPSG:4326" disabled={uploading} /></label>
            <label>DBF 字符集<Input value={dbfCharset} onChange={(event) => setDbfCharset(event.target.value)} placeholder="自动识别；可填 GBK / UTF-8" disabled={uploading} /></label>
          </div>
        </details>

        {uploading && (
          <div className="upload-progress" aria-live="polite">
            <Progress percent={progress} status="active" />
            <Button danger onClick={(event) => { event.stopPropagation(); controller.current?.abort(); }}>取消上传</Button>
          </div>
        )}
        {error && <Alert className="block-alert" type="error" showIcon title="导入失败" description={error} closable onClose={() => setError("")} />}
      </section>

      {dataset && (
        <>
          <section className="content-card" aria-labelledby="dataset-title" data-testid="dataset-summary">
            <div className="section-heading">
              <div><div className="eyebrow">已就绪</div><Typography.Title id="dataset-title" level={3}>数据检查结果</Typography.Title></div>
              <Space><Tag color="cyan">{dataset.format}</Tag><Button icon={<DeleteOutlined />} onClick={onClear}>移除</Button></Space>
            </div>
            <Descriptions column={{ xs: 1, sm: 2, lg: 4 }} size="small">
              <Descriptions.Item label="要素总数">{dataset.featureCount.toLocaleString("zh-CN")}</Descriptions.Item>
              <Descriptions.Item label="有效几何">{dataset.validGeometryCount.toLocaleString("zh-CN")}</Descriptions.Item>
              <Descriptions.Item label="修复几何">{dataset.repairedGeometryCount.toLocaleString("zh-CN")}</Descriptions.Item>
              <Descriptions.Item label="坐标系">{dataset.crs}</Descriptions.Item>
              <Descriptions.Item label="图层">{dataset.layers.map((layer) => layer.name).join("、") || "—"}</Descriptions.Item>
              <Descriptions.Item label="编码">{dataset.sourceEncoding || "不适用 / 自动"}</Descriptions.Item>
              <Descriptions.Item label="范围" span={2}>{dataset.bboxWgs84.map((value) => value.toFixed(6)).join(", ")}</Descriptions.Item>
            </Descriptions>
            {dataset.issues.length > 0 && (
              <Alert
                className="block-alert"
                type={dataset.issues.some((issue) => issue.severity === "ERROR") ? "error" : "warning"}
                showIcon
                title={`发现 ${dataset.issues.length} 类数据问题`}
                description={dataset.issues.map((issue) => `${issue.code}（${issue.count}）：${issue.message}`).join("；")}
              />
            )}
            <Typography.Title level={5}>字段概览</Typography.Title>
            <Table
              size="small"
              rowKey="name"
              pagination={{ pageSize: 8, hideOnSinglePage: true }}
              dataSource={dataset.fields}
              columns={[
                { title: "字段", dataIndex: "name" },
                { title: "类型", dataIndex: "type", responsive: ["sm"] },
                { title: "非空", dataIndex: "presentCount" },
                { title: "数值", dataIndex: "numericCount" },
                { title: "示例", dataIndex: "sampleValues", responsive: ["md"], render: (values: string[]) => values?.slice(0, 3).join(" · ") || "—" }
              ]}
            />
          </section>

          <section className="content-card" aria-labelledby="footprint-title">
            <div className="section-heading"><div><div className="eyebrow">空间复核</div><Typography.Title id="footprint-title" level={3}>建筑轮廓与范围</Typography.Title></div><FileSearchOutlined className="section-icon" /></div>
            <CesiumViewport
              source={{ kind: "footprints", url: `/api/datasets/${dataset.datasetId}/preview`, bounds: dataset.bboxWgs84 }}
              label="导入建筑轮廓预览"
              height={360}
            />
          </section>

          <div className="step-actions"><span /><Button type="primary" size="large" onClick={onNext} data-testid="import-next">配置建模参数</Button></div>
        </>
      )}
    </div>
  );
}
