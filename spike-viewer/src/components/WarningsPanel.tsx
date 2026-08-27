import { Alert, Pagination, Tag, Typography } from "antd";
import { useEffect, useState } from "react";
import { warningPage } from "../state/session";

interface Props {
  warnings: string[];
  title?: string;
}

export function WarningsPanel({ warnings, title = "警告与诊断" }: Props) {
  const [page, setPage] = useState(1);
  useEffect(() => setPage(1), [warnings]);
  if (warnings.length === 0) return <Alert type="success" showIcon title="没有警告" />;
  const visible = warningPage(warnings, page);
  return (
    <section aria-label={title}>
      <div className="section-heading compact-heading">
        <Typography.Title level={5}>{title}</Typography.Title>
        <Tag color="warning">{warnings.length.toLocaleString("zh-CN")} 条</Tag>
      </div>
      <ol className="warning-list">
        {visible.map((warning, index) => (
          <li key={`${(page - 1) * 50 + index}-${warning}`}>
            <Typography.Text><span className="warning-index">{(page - 1) * 50 + index + 1}</span>{warning}</Typography.Text>
          </li>
        ))}
      </ol>
      {warnings.length > 50 && (
        <Pagination
          className="warning-pagination"
          current={page}
          pageSize={50}
          total={warnings.length}
          showSizeChanger={false}
          onChange={setPage}
          aria-label="警告分页"
        />
      )}
    </section>
  );
}
