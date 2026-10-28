# P2 帧预算与 registry 配对开销探针

范围、工作量、采样计划和限制见 `DESIGN.md`。仅允许显式指定任务拥有的 API33+ 模拟器；它不改生产默认值，不操作手机，也不会把结果写成性能 gate PASS。

当前 host 通过并由主任务确认源码/AAR稳定后，在工程根目录使用已配置的 Python、JDK 与 Android SDK：

```powershell
$env:P2_BENCH_PYTHON = '<可用 Python 的绝对路径>'
& $env:P2_BENCH_PYTHON benchmarks/p2-overhead/prepare.py prepare
$env:JAVA_HOME = '<JDK 绝对路径>'
.\gradlew.bat -p benchmarks/p2-overhead --offline --dependency-verification strict --no-daemon --max-workers=1 :app:recordProbeBuild
```

`prepare` 只创建一次 `inputs/`，冻结当前 host 回执及真实 runtime AAR，并复制当前严格验证元数据。Gradle 校验 source、host、AAR、探针源码及校验元数据；如果输入变化则失败，必须使用新的隔离探针目录，不能覆盖旧输入。测试实现在同一个 androidTest APK，主 APK 不依赖 runtime。

选择仅由本任务创建的 API33+ AVD，串口和 AVD 名称必须明确一致；结果目录必须为本探针 `results/` 下不存在的新目录：

```powershell
& $env:P2_BENCH_PYTHON benchmarks/p2-overhead/run_processes.py --serial emulator-<port> --avd <本任务 AVD 名> --output benchmarks/p2-overhead/results/<新目录>
& $env:P2_BENCH_PYTHON benchmarks/p2-overhead/analyze.py benchmarks/p2-overhead/results/<新目录>
```

安装前若任一同名包已存在，只有两个 APK 的安装回读 SHA 都与本次一致才复用；否则停止。6 个进程均须独立执行一个 JUnit 合同、4 轮预热和 20 对测量块，保存全部原始 JSON 与 instrumentation。分析结果按进程聚合，不输出 p99，模拟器物理性能 gate 始终 BLOCKED。
