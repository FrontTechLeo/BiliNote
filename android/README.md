# BiliNote Android 客户端

这是 BiliNote 后端的原生 Android 客户端。应用不包含下载、转录或 AI 处理服务，只通过设置中的服务地址和可选 Token 连接现有后端。

## 配置

- 在“设置”中填写 `http://` 或 `https://` 服务地址；地址会自动去除末尾 `/`。
- Token 只用于请求认证，应用使用 Android Keystore 加密的 DataStore 保存，不写入日志，也不要提交到源码、截图或 issue。
- 服务地址应指向后端根地址；客户端依赖 `/sys_health`、`/single/tasks`、`/task_status/{task_id}`、`/generate_note`、`/upload`、`/export_note/{task_id}`，以及合集所需的 `/batch/preview/*`、`/batch/tasks/*` 路由。
- 自托管发布前必须确认反向代理转发上述路由、允许需要的 `POST`/`PATCH`/`DELETE`/文件上传，并正确返回 JSON 错误码与导出二进制响应。
- 认证由后端启用并校验；若使用 Token，服务端应限制权限、设置过期/轮换策略并避免把 Token 回显到响应或日志。

## 设备布局

- 手机：底部导航与单列页面。
- 中等宽度平板：导航 Rail + 列表/详情两栏。
- 宽屏平板：导航 Rail + 合集列表 + 子任务列表 + 详情三栏。

## 后台同步

`WorkScheduler.schedule(context)` 注册唯一的六小时周期任务，并要求网络已连接。同步只刷新单任务和合集快照；网络失败会重试，失败时不会删除 Room 缓存。服务地址未配置或鉴权失败时任务失败并等待用户处理。

## 构建前提

项目要求 JDK 17 和 Android SDK 35。项目已包含 Gradle Wrapper；在 Windows PowerShell 中从 `android/` 目录执行：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
# 运行所有模块的 Debug 单元测试
.\gradlew.bat testDebugUnitTest
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:connectedDebugAndroidTest  # 设备可用时执行
```

如需运行 benchmark 或 baseline profile，先查看模块实际生成的任务，再执行对应的 connected 任务：

```powershell
.\gradlew.bat :benchmark:tasks --all
.\gradlew.bat :baselineprofile:tasks --all
# 按上面列出的实际任务名执行，例如：
.\gradlew.bat :benchmark:connectedDebugAndroidTest
.\gradlew.bat :baselineprofile:connectedBenchmarkReleaseAndroidTest
```

发布前请在真实手机、800dp 平板和 1280dp 横屏设备上验证网络断开、服务地址切换、本地视频、多任务轮询、Markdown 图片与 ZIP 导出。

## HTTPS 与发布检查

- 公网或生产环境只使用 HTTPS；不要通过明文 HTTP 传输 Token 或上传内容。仅限本机调试时才使用 `http://localhost` 等地址。
- 生产服务应配置 TLS、认证、访问控制、上传大小限制、请求超时、CORS/反向代理规则和审计日志脱敏。
- 发布包不应包含测试后端地址、Token、调试开关或 benchmark 入口；发布前执行 Release 构建并在断网后确认 Room 缓存仍可读。
- WorkManager 只在有网络约束下刷新任务快照；网络或服务失败不会清除现有缓存，恢复网络后由唯一周期任务重试。
