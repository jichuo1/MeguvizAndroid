# MeguvizAndroid

Android 生命周期、窗口、资源和有界调度基础库。当前源码版本为 `0.1.0-SNAPSHOT` 开发版本，尚未发布到 Maven Central。

模块：`android-runtime`、`android-window`、可选 `android-lifecycle` 与 `android-resources-loader`；`samples/` 是独立 Maven 消费者，`testkit/` 和 `benchmarks/` 服务于开发验证。核心模块不反向依赖 UI、反射或 Hook 框架。

## 验证

使用 JDK 25 或 27、Android SDK API 37 与 build-tools 37.0.0；Windows 设置 `JAVA_HOME` 后执行：

```powershell
.\scripts\run-gradle.ps1 verifyHost
```

本仓库使用独立窗口测试 fixture。首次在新检出环境运行时，以本次真实结果为准；不要把其他源码或其他环境的报告当作本仓库的通过证据。设备、物理性能和生产场景另行验证；GitHub Actions 仅支持手动触发。

当前生产准备、版本及未验证范围见 [开发状态](docs/DEVELOPMENT_STATUS.md)。源代码以 Apache-2.0 提供，依赖的许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
