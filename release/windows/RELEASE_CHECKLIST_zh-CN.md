# Vector2World Windows 发布与回滚清单

## RC 与正式版

1. 在干净工作树执行前后端测试和 `build-release.ps1`；RC 可为 `UNSIGNED_RC`，正式版必须从精确 `v<version>` tag 构建。
2. 正式版在 CI secret 中配置 `VECTOR2WORLD_SIGN_PFX_BASE64` 与 `VECTOR2WORLD_SIGN_PASSWORD`。构建脚本会签名 EXE/MSI，验证脚本会要求 Authenticode 状态为 `Valid`。
3. 保留完整的 `release-metadata.json`、`SHA256SUMS.txt`、Java/Web SBOM、许可证和 GitHub Actions 日志。
4. Windows 2022/2025 必须通过便携双 profile smoke 和样例 import → preview → generate → report/tileset E2E；安装任务必须通过 0.9.0 基线升级、repair、uninstall 和用户数据保留。
5. Release notes 至少列出产品版本/build、Git SHA、OSM2World SHA、签名状态、支持系统、SHA-256、已知限制、升级与卸载语义。
6. 推送 tag 前运行 `preflight-production.ps1`；tag workflow 会先创建 draft Release，重新下载并验证哈希和 Authenticode 后才公开发布。

## 发布规则

- 每次构建使用新的 `<version>+<buildNumber>` 目录；禁止覆盖同名制品或复用不明内容的 tag。
- 只发布已经通过 `verify-release.ps1` 的 MSI/ZIP；公开下载页同时发布 SHA-256 和 SBOM。
- 降级不是原位 MSI 操作：先卸载当前二进制（保留用户数据），再安装旧版。旧版若遇到更高版本配置 schema 会拒绝启动且不覆盖原配置。

## 撤回与回滚

1. 发现阻断问题后立即停止推广并把发布条目标记为 withdrawn；保留原制品和哈希用于审计，不以同 tag 静默替换。
2. 发布修复版时递增 SemVer/build，重新执行签名、双 Windows E2E、升级矩阵、SBOM 与哈希门禁。
3. 如需临时回滚，向用户提供上一已验证版本及其原始 SHA-256，并明确要求先卸载当前二进制；不得删除 `%LOCALAPPDATA%\Vector2World`。
4. 配置迁移失败时使用 `config/settings.properties.backup-*` 恢复；未来 schema 拒绝属于保护行为，不应通过手工降低 schema 号绕过。
