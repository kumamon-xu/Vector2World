# Vector2World

Vector2World 是基于 [OSM2World](https://github.com/tordanik/OSM2World) 的二次开发项目，用于把带高度属性的 Shapefile 或 GeoJSON 建筑面转换为可验证、可预览、可下载的 3D Tiles。

项目提供完整的 Windows 本地工作台：导入矢量、检查 CRS/编码/几何、配置高度与建筑样式、抽样预览、批量建模、CesiumJS 验证和成果交付。应用仅在本机环回地址运行，不需要部署公网服务。

当前产品版本：`1.0.1`<br>
OSM2World 基线：`0.5.0-SNAPSHOT` / `bfa31df1124295721ec848273fbf93ab46b24d25`<br>
规则与预设版本：`m2-rules-v1` / `m2-presets-v1`

## 目录

- [主要能力](#主要能力)
- [输入与输出](#输入与输出)
- [处理流程](#处理流程)
- [快速开始](#快速开始)
- [从源码构建](#从源码构建)
- [开发模式](#开发模式)
- [API 概览](#api-概览)
- [数据目录与生命周期](#数据目录与生命周期)
- [配置与容量](#配置与容量)
- [架构](#架构)
- [测试](#测试)
- [Windows 打包与发布](#windows-打包与发布)
- [安全设计](#安全设计)
- [故障排查](#故障排查)
- [已知限制](#已知限制)
- [许可证](#许可证)

## 主要能力

- 导入 GeoJSON 或完整 Shapefile ZIP。
- 识别 CRS、字符编码、字段类型、范围和几何统计。
- 支持 Polygon、MultiPolygon、洞和可修复的无效建筑面。
- 高度字段可选择 `m`、`cm`、`mm` 或 `ft`，进入模型前统一换算为米。
- 异常高度可选择跳过并报告，或立即失败。
- 提供三种屋顶模式和四套确定性风格预设。
- 使用 OSM2World 生成程序化建筑模型。
- 默认按 Z15、centroid-owner 策略生成 GLB-based 3D Tiles 1.1。
- 支持代表性样例预览和完整异步生成任务。
- 提供 SSE 进度、取消、超时、有限重试、诊断日志和失败任务干净重跑。
- 在 CesiumJS 中加载导入范围、样例模型和最终 3D Tiles。
- 下载完整 ZIP，或通过受控本地接口打开成果目录。
- Windows 便携版和 MSI 均自带裁剪后的 Java 17 runtime。
- 输出 manifest、generation report、版本信息、许可证、SBOM 和 SHA-256。

## 输入与输出

### GeoJSON

- 文件扩展名：`.geojson` 或 `.json`。
- 支持声明 CRS；缺失 CRS 时应在导入高级选项中明确填写，例如 `EPSG:4326`。
- 默认严格尝试 UTF-8；当前 reader 也能识别已验证样例所用的 GB18030。
- 只提取 Polygon、MultiPolygon，以及 GeometryCollection 中的 polygonal 内容。

### Shapefile

请把同一图层的必要 sidecar 文件打包为一个 ZIP：

```text
buildings.zip
├── buildings.shp
├── buildings.shx
├── buildings.dbf
├── buildings.prj
└── buildings.cpg    # 可选；建议提供
```

要求：

- `.shp`、`.shx`、`.dbf`、`.prj` 必须齐全并使用同一基名。
- 如 DBF 没有 `.cpg`，可在导入高级选项中指定字符集。
- ZIP 会执行路径穿越、重复条目、条目数量、展开体积和压缩比检查。

### 高度字段

高度字段必须能转换为有限正数。示例数据使用：

```text
字段：Elevation
单位：m
```

可选单位为 `m`、`cm`、`mm`、`ft`。默认最大允许高度为 `10000 m`。

### 输出成果

当前产品输出白名单仅包含 `3DTILES`。虽然 OSM2World 源码中还有其他 exporter，但未完成同等级产品验证的格式不会在 API 中开放。

一次成功任务通常包含：

```text
result/
├── tileset.json
├── index/
│   └── .../*.tileset.json
├── lod2/
│   └── .../*.glb
├── manifest.json
└── generation-report.json
```

- `tileset.json`：3D Tiles 根树。
- `*.tileset.json` / `*.glb`：分块索引和模型内容。
- `manifest.json`：输入、CRS、高度映射、规则版本、构建版本和 Tile 所有权信息。
- `generation-report.json`：耗时、建筑数、Tile 数、三角形、体积、warning 和失败明细。
- 下载接口返回只包含已发布结果树的流式 ZIP。
- 诊断接口返回经过限额和脱敏处理的日志 ZIP。

## 处理流程

```text
SHP ZIP / GeoJSON
        │
        ▼
CRS、编码、字段与几何检查
        │
        ▼
高度标准化（统一为米）
        │
        ▼
确定性建筑规则与 OSM 标签
        │
        ▼
OSM2World 程序化建模
        │
        ▼
Z15 分块、LOD2 GLB、3D Tiles 树
        │
        ▼
结构验证、Cesium 预览、报告与下载
```

Web UI 使用四步工作流：

1. **导入数据**：上传文件并检查输入质量。
2. **配置模型**：选择高度字段、单位、异常策略、屋顶和风格。
3. **样例预览**：稳定抽取代表性建筑进行规则与视觉复核。
4. **生成交付**：处理全部有效建筑，验证并下载成果。

## 快速开始

### Windows MSI

1. 运行 `Vector2World-1.0.0.msi`。
2. 从桌面或开始菜单启动 Vector2World。
3. 应用会监听 `127.0.0.1` 的动态空闲端口并自动打开浏览器。
4. 导入 GeoJSON，或导入包含完整 sidecar 的 Shapefile ZIP。
5. 选择高度字段和单位，生成样例并完成全量任务。
6. 在最终页面下载 ZIP 或打开成果目录。

MSI 按当前用户安装，不要求管理员权限。升级、修复和卸载默认不会删除用户生成的数据。

### Windows 便携版

1. 解压 `Vector2World-<version>-windows-x64-portable.zip`。
2. 进入 `Vector2World` 目录。
3. 双击 `Vector2World.exe`。
4. 可使用包内 `sample/building-sample.geojson` 验收完整流程。

便携版同样自带 Java runtime，不依赖系统 JDK、Maven 或 Node.js。关闭浏览器不会终止正在运行的任务；请通过系统托盘的 **Exit** 或启动器控制台退出应用。

## 从源码构建

### 环境要求

- Windows 10/11 x64；日常 Java 构建也可在其他受支持的 JDK 平台尝试。
- JDK 17。
- Node.js 24 和 npm。
- Git。
- Maven 可使用仓库自带 Wrapper `3.9.16`，无需全局安装。
- 只有生成 MSI 时才需要 WiX Toolset 3.14 的 `candle.exe` 与 `light.exe`。

检查环境：

```powershell
java -version
node --version
npm --version
.\mvnw.cmd -version
```

### 构建前端和可执行 JAR

```powershell
cd spike-viewer
npm ci
npm run check

cd ..
.\mvnw.cmd -pl building-tiler-backend -am clean package
```

构建结果：

```text
building-tiler-backend/target/building-tiler-backend-0.5.0-SNAPSHOT.jar
```

启动：

```powershell
java -jar .\building-tiler-backend\target\building-tiler-backend-0.5.0-SNAPSHOT.jar
```

不自动打开浏览器：

```powershell
java -jar .\building-tiler-backend\target\building-tiler-backend-0.5.0-SNAPSHOT.jar --no-browser
```

启动日志中的 `VECTOR2WORLD_READY http://127.0.0.1:<port>/` 是本次实例地址。

## 开发模式

前端开发代理默认指向 `http://127.0.0.1:18080`。

先构建并启动后端固定端口：

```powershell
cd spike-viewer
npm ci
npm run build

cd ..
.\mvnw.cmd -pl building-tiler-backend -am package -DskipTests
java -jar .\building-tiler-backend\target\building-tiler-backend-0.5.0-SNAPSHOT.jar `
  --no-browser --server.port=18080
```

再打开另一个终端启动 Vite：

```powershell
cd spike-viewer
npm run dev
```

如使用其他后端端口，修改 `spike-viewer/.env.development`：

```dotenv
VITE_API_TARGET=http://127.0.0.1:18080
```

生产模式不会使用 Vite dev server；前端 build 和 Cesium assets 会被复制进 Spring Boot 静态资源。

## API 概览

API 返回版本化 JSON，错误响应包含稳定的 `code` 和可操作信息。主要入口：

| Method | Path | 用途 |
|---|---|---|
| `GET` | `/api/system/health` | 本地服务健康检查 |
| `GET` | `/api/system/about` | 产品与上游版本追溯 |
| `POST` | `/api/system/open-directory` | 打开受控 dataset/job 目录 |
| `POST` | `/api/datasets` | 上传并检查 GeoJSON 或 SHP ZIP |
| `GET` | `/api/datasets/{id}` | 获取数据集 metadata |
| `POST` | `/api/datasets/{id}/height-mapping` | 配置高度字段和单位 |
| `GET` | `/api/datasets/{id}/preview` | 获取导入轮廓预览 |
| `DELETE` | `/api/datasets/{id}` | 幂等删除数据集 |
| `POST` | `/api/model-previews` | 生成代表性建模样例 |
| `GET` | `/api/model-previews/{id}` | 查询样例状态与链接 |
| `POST` | `/api/jobs` | 创建异步全量任务 |
| `GET` | `/api/jobs/{id}` | 查询任务状态 |
| `GET` | `/api/jobs/{id}/events` | SSE 进度、replay 与 heartbeat |
| `DELETE` | `/api/jobs/{id}` | 取消任务 |
| `GET` | `/api/jobs/{id}/manifest` | 获取生成 manifest |
| `GET` | `/api/jobs/{id}/report` | 获取生成报告 |
| `GET` | `/api/jobs/{id}/files/{path}` | 读取受控 tileset/GLB 资产 |
| `GET` | `/api/jobs/{id}/download` | 下载完整成果 ZIP |
| `GET` | `/api/jobs/{id}/diagnostics` | 下载脱敏诊断 ZIP |
| `POST` | `/api/jobs/{id}/retry-failed` | 使用原 spec 创建隔离的恢复任务 |

完整契约位于：

- `building-tiler-backend/src/main/resources/contracts/vector2world-m1.openapi.yaml`
- `building-tiler-backend/src/main/resources/contracts/vector2world-m2.openapi.yaml`
- `building-tiler-backend/src/main/resources/contracts/vector2world-m3.openapi.yaml`
- 同目录下的 metadata、config、manifest、report 和 benchmark JSON Schema。

## 数据目录与生命周期

Windows 默认根目录：

```text
%LOCALAPPDATA%/Vector2World/
├── config/
│   └── settings.properties
├── cache/
└── data/
    └── instances/
        └── <instance-id>/
            ├── datasets/
            ├── previews/
            ├── jobs/
            └── logs/vector2world.log
```

- 每次启动默认生成独立 instance ID，多个进程不会共享 Job 工作目录。
- Preview 默认保留 2 小时，Job 默认保留 24 小时；后台定期清理过期目录。
- MSI 卸载不会静默删除 `%LOCALAPPDATA%/Vector2World`。
- 配置文件使用 schema version；迁移前会备份，损坏配置会备份后恢复默认值。
- 比当前程序更新的配置 schema 会被拒绝，原文件不会被覆盖。

自定义整个产品根目录：

```powershell
Vector2World.exe --data-root="D:\Vector2World Data"
```

也可以关闭程序后编辑 `config/settings.properties`，只改变实例数据目录：

```properties
schema.version=1
data.root=D:/Vector2World-Data
```

只支持可写的本机绝对路径；当前不支持 UNC 网络路径。

## 配置与容量

主要后端配置位于 `building-tiler-backend/src/main/resources/application.properties`：

| 配置 | 默认值 | 说明 |
|---|---:|---|
| `spring.servlet.multipart.max-file-size` | `256MB` | 单次上传上限 |
| `vector2world.previews.ttl-hours` | `2` | Preview 保留时间 |
| `vector2world.jobs.ttl-hours` | `24` | Job 保留时间 |
| `vector2world.jobs.queue-capacity` | `128` | 全局有界工作队列 |
| `vector2world.jobs.maximum-job-bytes` | `8 GiB` | 单 Job 输出配额 |
| `vector2world.jobs.maximum-zip-bytes` | `8 GiB` | 成果 ZIP 配额 |
| `vector2world.jobs.maximum-features-per-tile` | `100000` | 单 Tile feature 硬上限 |
| `vector2world.jobs.job-timeout-minutes` | `360` | Job 超时 |
| `vector2world.jobs.tile-timeout-minutes` | `30` | 单 Tile 超时 |
| `vector2world.jobs.maximum-log-bytes` | `8 MiB` | 单类结构化日志上限 |

jpackage 默认 JVM 参数：

```text
-Xms256m -Xmx3g -XX:+UseG1GC
```

已验证 100,000 个合成建筑可在 2 GiB heap、4 workers、queue 128 下完成；面向实际 100k 数据建议至少保留 3 GiB heap 和 2 GiB 可用磁盘。复杂 footprint、材质和跨 Tile 分布会改变实际开销。

## 架构

```text
React 19 + Ant Design + CesiumJS
                  │
                  │ HTTP / SSE（同源、loopback）
                  ▼
Spring Boot API / Application Services
                  │
        ┌─────────┴─────────┐
        ▼                   ▼
GeoTools/JTS GIS Adapter    Job / Resource Policy
        │                   │
        └─────────┬─────────┘
                  ▼
Deterministic Building Rules
                  ▼
Osm2WorldEngineAdapter
                  ▼
TilesetWriterAdapter + Validator
                  ▼
GLB / 3D Tiles / Manifest / Report
```

核心边界：

- Web/API/Application 层不直接依赖 `org.osm2world.*`。
- GeoTools 输入对象不会越过 GIS adapter。
- 所有坐标先标准化为 WGS84，再进入 Tile-local 米制投影和 ECEF 输出。
- 规则输出由稳定 feature ID、规则版本和配置决定，不使用运行时随机数。
- 输出先写 staging，结构验证成功后再发布，失败结果不会伪装成 `COMPLETED`。

仓库结构：

| 目录 | 说明 |
|---|---|
| `core/` | OSM2World 平台无关核心 |
| `core-jvm/` | OSM2World JVM 转换与输出实现 |
| `core-web/` | OSM2World Web 相关模块 |
| `desktop/`、`opengl/` | OSM2World 原有桌面/OpenGL 模块 |
| `building-tiler-backend/` | Vector2World GIS、建模、Job、API 和产品启动器 |
| `spike-viewer/` | React/Cesium 四步工作台 |
| `release/windows/` | Windows 构建、smoke、E2E、安装生命周期和发布校验脚本 |
| `.github/workflows/` | Windows RC/正式发布流水线 |

## 测试

### 后端和上游 reactor

```powershell
.\mvnw.cmd -pl building-tiler-backend -am test
```

### 前端

```powershell
cd spike-viewer
npm ci
npm run check
```

`npm run check` 依次执行 ESLint、TypeScript、Vitest 和 production build。

### 打包验收

```powershell
.\release\windows\smoke-release.ps1 `
  -AppImage .\output\release\<release-id>\portable\Vector2World `
  -WorkRoot .\output\smoke

.\release\windows\e2e-release.ps1 `
  -AppImage .\output\release\<release-id>\portable\Vector2World `
  -WorkRoot .\output\packaged-e2e

.\release\windows\verify-release.ps1 `
  -ReleaseRoot .\output\release\<release-id>
```

安装生命周期脚本支持普通安装，也支持真实旧版升级：

```powershell
.\release\windows\test-installer-lifecycle.ps1 `
  -PreviousMsi .\Vector2World-0.9.0.msi `
  -Msi .\Vector2World-1.0.0.msi `
  -ExpectedVersion 1.0.0
```

当前 M6 验收基线：Maven reactor 512 tests、前端 11 tests，均为 0 failure/0 error；打包 Playwright 主路径、Cesium、便携双实例、API E2E 和 MSI 安装/升级/修复/卸载已通过。

## Windows 打包与发布

### 生成便携 ZIP 和 MSI

准备 WiX 3.14 的 `candle.exe`、`light.exe` 后执行：

```powershell
.\release\windows\build-release.ps1 `
  -Version 1.0.1 `
  -PackageVersion 1.0.1 `
  -BuildNumber local001 `
  -WixBin C:\Tools\wix314
```

输出位于：

```text
output/release/1.0.1+local001/
├── Vector2World-1.0.1+local001-windows-x64-portable.zip
├── installer/Vector2World-1.0.1.msi
├── portable/Vector2World/
├── sample/
├── legal/
├── sbom/
├── release-metadata.json
└── SHA256SUMS.txt
```

只生成便携 RC：

```powershell
.\release\windows\build-release.ps1 -BuildNumber local-portable -SkipInstaller
```

发布脚本拒绝覆盖已有 `<version>+<buildNumber>` 目录。

### 正式发布

正式发布要求：

- Git 工作树干净。
- HEAD 精确对应 `v<version>` tag。
- 提供 `VECTOR2WORLD_SIGN_PFX` 和 `VECTOR2WORLD_SIGN_PASSWORD`。
- EXE/MSI Authenticode 签名状态为 `Valid`。
- SHA-256、SBOM、许可证、双 Windows E2E 和安装升级矩阵全部通过。

GitHub Actions workflow 位于 `.github/workflows/windows-release.yml`，目标环境为 `windows-2022` 和 `windows-2025`。完整发布与回滚规则见 `release/windows/RELEASE_CHECKLIST_zh-CN.md`。

## 安全设计

- 服务固定绑定 `127.0.0.1`，禁用 forwarded headers，不监听局域网或公网地址。
- 端口由系统动态分配，避免固定端口冲突。
- “打开目录”接口只接受 `dataset|job` 和 UUID，不接受任意路径、命令或 URI。
- 受控路径使用 real path 校验，阻止 traversal、UNC、已删除路径和链接逃逸。
- 数据集、Preview、Job 和 staging 使用 UUID/实例目录隔离。
- ZIP 上传和解压设置体积、条目数量、压缩比与路径安全边界。
- Job 使用有界 worker pool、queue、磁盘/输出配额、timeout 和 cancellation point。
- 诊断日志限制大小并脱敏，不包含 token、原始用户属性或绝对路径。
- 正式发布需要 clean tag、代码签名、SBOM、许可证和校验和。

不要把应用配置为 `0.0.0.0`，也不要通过反向代理将当前 API 暴露到公网；项目没有提供公网认证和多用户隔离模型。

## 故障排查

### 页面没有自动打开

查看控制台中的：

```text
VECTOR2WORLD_READY http://127.0.0.1:<port>/
```

复制该地址到浏览器。确认安全软件没有阻止本机环回连接。

### Shapefile 导入失败

- 确认 ZIP 中 `.shp/.shx/.dbf/.prj` 齐全且同名。
- 尽量提供 `.cpg`；否则在高级选项指定 DBF 编码。
- 不要在 ZIP 中嵌套多个同名图层或加入路径穿越条目。

### 提示缺失 CRS

项目不会猜测未知 CRS。请补充 `.prj`、GeoJSON CRS，或在导入时明确填写 `EPSG:<code>`。

### 高度全部无效

- 检查字段名是否正确。
- 检查单位是否为 `m/cm/mm/ft` 中的实际单位。
- 检查值是否为有限正数，并确认最大高度限制。

### 生成失败或空间不足

- 查看 UI 报告和 `diagnostics` 下载。
- 查看实例目录中的 `logs/vector2world.log`。
- 确保数据目录可写，并至少预留 2 GiB 磁盘空间处理约 100k 建筑。
- 减少并发任务；不要通过取消输出验证来换取完成状态。

### 配置迁移失败

查看 `config/settings.properties.backup-*`。未来 schema 被旧程序拒绝是保护行为，不要手工降低 schema 号覆盖原配置。

### 需要完全清理

卸载程序后，确认成果已备份，再手动删除：

```text
%LOCALAPPDATA%/Vector2World
```

## 已知限制

- 当前仅正式设计和验证 Windows x64 本地产品。
- 当前输出格式仅为 3D Tiles；其他 OSM2World exporter 尚未进入产品白名单。
- 默认仅生成 LOD2；LOD4 在真实基准中体积增长显著，未作为 MVP 输出开放。
- 不支持 UNC 数据根目录、自动更新、文件关联、公网部署或多用户认证。
- Job 失败重跑使用全新 Job/staging；尚未提供跨进程重启的选择性 Tile resume。
- GeoJSON 大数据读取仍不是磁盘 backed 常量内存方案；百万级输入需要后续专项设计。
- Cesium/WebGL 表现受浏览器、GPU 和驱动影响。
- 程序化屋顶、楼层和材质是规则化表达，不等同于测绘或 BIM 真值。
- RC 可以明确为 `UNSIGNED_RC`；正式发布必须提供有效签名证书。

## 许可证

本仓库继承 OSM2World 的 MIT License，详见 [LICENSE.txt](LICENSE.txt)。

分发包还包含：

- OSM2World MIT 许可文本。
- Java 依赖归属报告。
- Java/Web CycloneDX SBOM。
- CesiumJS、GeoTools、JTS、Spring、React、Ant Design 等第三方组件的 notice 与相应许可证信息。

第三方组件的版权与许可证归各自权利人所有。

## 致谢

- [OSM2World](https://osm2world.org/)：核心程序化建模与 3D 输出能力。
- [GeoTools](https://geotools.org/) 与 [JTS](https://locationtech.github.io/jts/)：GIS、CRS 和几何处理。
- [CesiumJS](https://cesium.com/platform/cesiumjs/)：3D Tiles 浏览与验证。
- Spring Boot、React、Ant Design、Vite 及所有依赖项目的贡献者。
