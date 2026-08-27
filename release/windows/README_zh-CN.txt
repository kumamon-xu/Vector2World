Vector2World Windows 本地版
=========================

用途
----
将带高程字段的 GeoJSON 或 Shapefile 建筑面转换为可验证的 3D Tiles，并可选择下载 OSM2World 管线的现有成果。

快速开始
--------
1. 双击 Vector2World.exe。程序仅监听 127.0.0.1，并自动选择空闲端口。
2. 浏览器打开后导入 .geojson，或把 .shp/.shx/.dbf/.prj 一起压缩为 ZIP 后导入。
3. 选择高程字段和单位；随包样例使用 Elevation，单位为米。
4. 完成配置、样例预览、全量生成；待 Cesium 验证后下载 ZIP 或打开成果目录。
5. 关闭浏览器不会结束建模任务。通过系统托盘 Vector2World 图标选择 Exit，或关闭启动器控制台。

数据位置
--------
默认位于 %LOCALAPPDATA%\Vector2World。配置、缓存、日志及每次启动的任务目录与安装目录分离。
卸载或升级不会删除这些用户数据。若要彻底清理，请先自行备份成果，再手动删除该目录。
如需把实例数据迁到本机其他磁盘，可关闭程序后编辑 config\settings.properties，设置绝对路径
data.root=D:/Vector2World-Data；不支持 UNC 网络路径。也可用 --data-root=<绝对路径> 改变整个产品数据根目录。

诊断
----
- “关于”窗口显示产品版本、Git SHA、dirty 标记、构建时间、OSM2World 提交及规则/预设版本。
- 日志位于每个实例目录的 logs\vector2world.log。
- 端口由系统动态分配，不需要修改防火墙，也不对局域网开放。
- 不能启动时，确认用户数据目录可写且磁盘空间足够；程序不需要系统 JDK、Maven 或 Node.js。

限制
----
- 当前仅发布 Windows x64。
- 不提供自动更新、公共网络服务或多用户共享服务。
- 文件关联默认关闭；仅通过应用内受控按钮打开数据集/成果目录。
