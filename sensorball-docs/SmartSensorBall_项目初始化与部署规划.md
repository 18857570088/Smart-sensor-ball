# Smart sensor ball 项目初始化与部署规划

## 项目边界

- 老项目目录：`D:\2026\202603\3031`
- 新项目根目录：`D:\2026\202605\smartsensorball`
- 新项目名称：`Smart sensor ball`
- 内部代号：`sensorball`
- 目标：沿用当前认证、榜单、训练记录、成就与训练体系，拳击计数改为基于蓝牙协议数据，并使用独立 Android 工程、独立数据库、独立服务和独立 Nginx 入口。

## 目录规划

- Android：`D:\2026\202605\smartsensorball\sensorball-android`
- 服务端：`D:\2026\202605\smartsensorball\sensorball-server`
- 部署模板：`D:\2026\202605\smartsensorball\sensorball-deploy`
- 项目文档：`D:\2026\202605\smartsensorball\sensorball-docs`

## APP 规划

- 显示名称：`Smart sensor ball`
- applicationId：`com.zclei.smartsensorball`
- API Base URL：`http://152.136.62.157/sensorball/api/v1/`
- rootProject.name：`SmartSensorBallAndroid`

## 服务端规划

- 数据库：`sensorball`
- 服务目录：`/opt/sensorball-auth`
- 上传目录：`/opt/sensorball-auth/uploads`
- 日志目录：`/var/log/sensorball-auth`
- systemd：`sensorball-auth.service`
- 端口：`127.0.0.1:8013`
- Nginx 路径：`/sensorball/`

## 当前验证结果

- Android 包名、APP 名称和 API Base URL 已指向 Smart sensor ball 新线程。
- 服务端默认数据库为 `sensorball`，默认产品码为 `SSB01`，默认端口为 `8013`。
- Nginx 入口使用 `/sensorball/`，不覆盖原项目根入口。
- 公开健康检查 `http://152.136.62.157/sensorball/health` 返回 200。
- Debug APK 已输出到 `D:\2026\202605\smartsensorball\sensorball-deploy\apk\SmartSensorBall-debug.apk`。
