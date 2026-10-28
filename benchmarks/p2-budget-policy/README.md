# P2 预算策略配对验证

先读 `DESIGN.md`，本工程独立于已有 P2 开销探针。它在真实 Vsync 上比较固定任务的主动 deadline 延后和 unknown 固定配额回退，并保留逐帧工作、budget、延后与超期记录。模拟器计时仅属于合成消费者，不完成物理性能 gate。

当前 host 和 producer 固定后，在主工作区设置现有 Python、JDK 与 Android SDK，运行：

```powershell
$env:P2_BENCH_PYTHON = '<可用 Python 绝对路径>'
& $env:P2_BENCH_PYTHON benchmarks/p2-budget-policy/prepare.py prepare
$env:JAVA_HOME = '<JDK 绝对路径>'
.\gradlew.bat -p benchmarks/p2-budget-policy --offline --dependency-verification strict --no-daemon --max-workers=1 :app:recordProbeBuild
```

`prepare` 冻结本次 host、runtime AAR 和严格校验元数据；Gradle 构建前后重新核对其哈希及本工程源码。证据目录和失败日志须保留；同名 APK 已存在但哈希不符时停止，不能覆盖其他候选。仅使用明确指定的任务专属 API33+ 模拟器：

```powershell
& $env:P2_BENCH_PYTHON benchmarks/p2-budget-policy/run_processes.py --serial emulator-<port> --avd <本任务 AVD 名> --output benchmarks/p2-budget-policy/results/<新目录>
& $env:P2_BENCH_PYTHON benchmarks/p2-budget-policy/analyze.py benchmarks/p2-budget-policy/results/<新目录>
```

六个独立进程必须真实完成；每组每个测量块均须 48 项完整、无拒绝、无重复且 checksum 相同。若启用组在所有样本中没有实际延后，分析输出 `NOT_EXERCISED`，不声称验证了延后。模拟器数据不替代真实工作负载、物理设备、90/120 Hz 或正式 v1.0 的性能验收。
