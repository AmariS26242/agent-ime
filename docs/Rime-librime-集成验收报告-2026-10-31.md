# Rime / librime 集成验收报告（2026-10-31）

> 供验收方（GPT）逐条核对。每条结论都给出可复现命令或日志/文件证据；未能验证的部分单列在"未完成与风险"。

## 0. 验收结论速览

| 验收条件 | 结论 | 判据 |
|---|---|---|
| APK 含 `lib/arm64-v8a/librime_jni.so` | 通过 | `apkanalyzer files list` 输出（§4） |
| `isNativeRimeAvailable == true` | 通过 | logcat `librime ready, version=1.17.0` + `initialize=true`；界面显示 `librime 1.17.0 · 朙月拼音` |
| 26 键拼音候选 | 通过（第二轮已修排序） | `nihao` 首次候选即「你好」，由 `rank_pinyin_candidates` 保证，不再依赖用户词典学习（§0.2 ①） |
| 九键 T9 候选 | 通过（第二轮已修排序） | `64426`→12 种切分，首切分 `ni hao`（essay 21493 ＞ `ni gan` 1634），候选条首项「你好」（§0.2 ②） |
| 候选提交 | 通过 | 宿主输入框文本变化（§6） |
| 退格 / 回车 | 通过 | `pinyin 'niha'` / `commit 'niha' -> 'ok'` |
| Agent 内部输入 | 通过 | 草稿框得到"你好"，宿主框不变 |
| 输入框切换 | 通过（有缺陷） | `mServedView` 从 `editBackend` 切到 `editToken` 再切回均成立；但密码框候选抑制不稳定（§0.2 ⑤） |

**总判定：librime 已真实集成并可运行；第二轮修好了候选排序（26 键/九键首选词），仍有 3 项界面缺陷 + 4 项工程遗留，见 §8。**

## 0.1 验收脚本回放结果（2026-10-31 19:30–19:33，逐条）

| 脚本步骤 | 结果 | 实测输出 |
|---|---|---|
| 1 初始化 E 盘环境 | 通过 | `JAVA_HOME=E:\AgentImeTest\java\jdk-17.0.21.0.2+1`、`ANDROID_SDK_ROOT=E:\AgentImeTest\sdk`、`GRADLE_USER_HOME=E:\AgentImeTest\gradle-home` |
| 1b `icacls /grant A'maris:(OI)(CI)M /T` | 通过 | `Successfully processed 14546 files; Failed processing 0 files` |
| 2 `clean assembleDebug`（ASCII 联接路径） | 通过 | `BUILD SUCCESSFUL in 28s`，`48 actionable tasks: 47 executed`，退出码 0，日志 `E:\AgentImeTest\acceptance-build.log` |
| 3 `apkanalyzer files list` 过滤 | 通过 | 命中 `/lib/arm64-v8a/librime_jni.so`、`/lib/arm64-v8a/librime.so`、`/lib/x86_64/` 同套、`/assets/rime/`、`/assets/licenses/`（19 项） |
| 4 模拟器 | 通过（**适配**） | 已有实例 `emulator-5554` 且 `sys.boot_completed=1`，故未再 `Start-Process`（重复启动会报 "another emulator instance"）；`adb kill-server/start-server` 后设备仍在；`-list-avds` 输出 `AgentIme35` |
| 5 安装 + 启用 | 通过 | `Performing Streamed Install / Success`；`already enabled for user #0`；`selected for user #0`；`am start` 成功 |
| 6 读 Rime 日志 | 通过（**适配**） | 脚本直接读为空：IME 服务在**输入框获得焦点**时才绑定初始化，故补了一次点击 `(250,1102)` 聚焦后端地址框，随后得到 §6.1 全部日志 |
| 7 保存证据 | 通过 | `E:\AgentImeTest\rime-runtime-final.log`(58,417 B)、`rime-apk-final-list.txt`(25,388 B)、`rime-apk-final-sha256.txt` |

两处适配都不是代码问题：①模拟器常驻，重复启动无意义；②日志依赖焦点绑定，脚本需先触发键盘（或改为 `adb shell am broadcast`/手动点输入框）。

## 0.2 第二轮复验（2026-10-31 19:36–19:42，按六项口径逐条）

本轮先发现的**真实回归**：`pinyin 'nihao'` 曾返回 `first=你哈哦`，说明上一轮“你好登顶”靠的是用户词典学习（点候选走 UI commit，不经过 JNI），不稳定。已修 `rime_jni.c` 排序后复测。

| # | 复验项 | 结论 | 证据 |
|---|---|---|---|
| ① | 26 键 nihao 首选词是否稳定 | **通过（修后稳定）** | `pinyin ranking: 'nihao' min_syl=2 top='你好'`；`pinyin 'nihao' -> 9 candidates, first=你好`；连续 3 次重输（含 Agent 面板内）均同结果 |
| ② | 九键 64426 候选与提交 | **通过** | `t9 '64426' -> 12 segmentations, first='ni hao'`；`t9 ranking: 'ni hao' score=21493, 'ni gan' score=1634`；候选条 你好/妳好/逆號/擬好/你哈/你哈；点首项后宿主框变 `http://10你好.0.2.2:8787` |
| ③ | 退格、回车 | **通过** | 26 键：`pinyin 'niha'`（退格不清宿主）→ `commit 'niha' -> 'ok'`，宿主变 `http://10你哈.0.2.2:8787`；九键：退格把缓冲 `6644`→`664`，再按 2 得 `6642` |
| ④ | Agent 面板草稿框可输入且宿主不被修改 | **通过** | 草稿框输入 nihao → 候选首项「你好」→ 点选后草稿框显示“你好”；`editBackend` 前后均为 `http://10你好.0.2.2:8787`（未被写入）；面板底部显示 `librime 1.17.0 · 朙月拼音` |
| ⑤ | 普通框→密码框→普通框切换 | **通过但抑制不稳定（缺陷）** | `mServedView=...app:id/editBackend` → `...app:id/editToken`（`password="true"`）→ 再切回；19:20 聚焦密码框时候选被抑制且有 toast「目标已变化，拒绝写入」，19:40 同样聚焦却显示候选条（也/頁/葉/業/夜/野） |
| ⑥ | 缺陷复核 | **部分修正、部分仍存在** | 九键底部行**不再是“触摸无效”**（正确坐标 `(959,2133)` 退格生效），但该行贴边、热区小；Agent 面板横向溢出**仍存在**；密码框抑制不稳定为**新记录缺陷** |

本轮产物（`clean assembleDebug`，48 tasks / 47 executed，`BUILD SUCCESSFUL in 26s`，退出码 0；日志 `E:\AgentImeTest\rebuild-final.log`）：

- APK：`C:\Users\A'maris\Documents\ChatGPT\agent输入法\project\app\build\outputs\apk\debug\app-debug.apk`
- 大小 / SHA-256：**12,823,330 B** / `A811375E7FFDD58C4AF7CE7121DDADC1E26A37D5C385388BA886882E751A1240`（增量包 12,844,367 B / `101B9AF0...` 与上一轮 12,821,566 B / `09F2981D...` 均已作废，以本条 clean 产物为准）
- 内容核对：`apkanalyzer files list` 共 528 条；`/lib/arm64-v8a/` 12 条、`/lib/x86_64/` 12 条；含 `/lib/arm64-v8a/librime_jni.so`、`/lib/arm64-v8a/librime.so`；`/assets/rime/{default.yaml,luna_pinyin.schema.yaml,luna_pinyin.dict.yaml,essay.txt}`
- 装机复验：clean 包 `install -r` 成功 → `select_schema(luna_pinyin) = 1` → `librime ready, version=1.17.0` → `RimeEngine: librime initialize=true version=1.17.0`
- 证据：`E:\AgentImeTest\apk-files-clean-2026-10-31.txt`、`E:\AgentImeTest\rebuild-final.log`、`E:\AgentImeTest\logcat-rime-2026-10-31.log`、`E:\AgentImeTest\screens\{A1,A2,B2,B7,C5,C6,D1,D2,D3,E1,E2,E3,E4}*.png`


---

## 1. 环境与版本矩阵（全部在 E 盘）

| 组件 | 版本 / 路径 |
|---|---|
| JDK | 17.0.21.0.2+1 — `E:\AgentImeTest\java\jdk-17.0.21.0.2+1` |
| Android SDK | `E:\AgentImeTest\sdk`（platforms;android-35、build-tools;34.0.0/35.0.0） |
| NDK | 27.0.12077973 — `E:\AgentImeTest\sdk\ndk\27.0.12077973` |
| CMake | 3.31.6 — `E:\AgentImeTest\sdk\cmake\3.31.6` |
| Gradle | 8.11.1 — `E:\AgentImeTest\gradle\gradle-8.11.1`，缓存 `E:\AgentImeTest\gradle-home` |
| AGP / Kotlin | 8.5.2 / 1.9.24（`build.gradle.kts`、`app/build.gradle.kts`） |
| compileSdk / minSdk / targetSdk | 35 / 26 / 35 |
| librime | **1.17.0**（Termux bionic 预编译，非本机源码编译） |
| 引擎数据 | 朙月拼音 `luna_pinyin` + `essay.txt`（442,688 条词频） |
| 环境脚本 | `E:\AgentImeTest\env.ps1`（设 JAVA_HOME / ANDROID_SDK_ROOT / GRADLE_USER_HOME / PATH） |

**关键约束（必须知道）**：工程路径 `...\agent输入法\project` 含非 ASCII。AGP 自带 CMake 配置阶段会崩 `0xC0000409 STATUS_STACK_BUFFER_OVERRUN`。
已建 ASCII 目录联接：

```
C:\Users\A'maris\Documents\ChatGPT\agentime  ->  C:\Users\A'maris\Documents\ChatGPT\agent输入法\project
```

**所有构建必须在联接路径下执行**，否则 native 配置必崩。

---

## 2. 代码与资源改动清单

| 文件 | 类型 | 作用 |
|---|---|---|
| `app/src/main/cpp/CMakeLists.txt` | 新增 | 编 `rime_jni`（SHARED），链接 `jniLibs/<abi>` 下整条 native 依赖 |
| `app/src/main/cpp/rime_jni.c` | 新增（523 行） | JNI 实现：初始化、26 键候选、九键候选、提交 |
| `app/src/main/cpp/include/rime_api.h` 等 4 个头文件 | 新增 | librime C API 头（来自 librime 1.17.0 包） |
| `app/src/main/cpp/t9_syllables.h` | 新增 | 424 个拼音音节表（由词库列生成），T9 切分用 |
| `app/src/main/jniLibs/arm64-v8a/*.so`（10 个） | 新增 | librime 及闭包预编译库 |
| `app/src/main/jniLibs/x86_64/*.so`（10 个） | 新增 | 同上（模拟器用） |
| `app/src/main/assets/rime/{default,luna_pinyin.schema,luna_pinyin.dict}.yaml`、`essay.txt` | 新增 | 引擎配置、schema、词库、词频 |
| `app/src/main/assets/licenses/*`（14 个） | 新增 | 第三方许可证 + `NOTICE.md` |
| `app/src/main/java/app/agentime/ChineseInputEngine.kt` | 重写 | 接口加 `prepare()`；`RimeData` 负责把 assets 部署到 filesDir；引擎就绪判断与回退 |
| `app/src/main/java/app/agentime/AgentImeService.kt` | 修改 | `:62` 调用 `prepare()`；`:258` 退格改缓冲；`:268/272` 回车分九键/拼音；`:544` 面板引擎标识动态化 |
| `app/build.gradle.kts` | 修改 | `ndkVersion`、`abiFilters`、`externalNativeBuild`(cmake 3.31.6)、`jniLibs.useLegacyPackaging` |
| `gradle.properties` | 修改 | `android.overridePathCheck=true`（非 ASCII 路径所需） |

关键行号定位：

- `ChineseInputEngine.kt:44` `System.loadLibrary("rime_jni")`
- `ChineseInputEngine.kt:61` `object RimeData`
- `ChineseInputEngine.kt:155` `override fun prepare(context: Context)`
- `AgentImeService.kt:62` `chineseInputEngine.prepare(applicationContext)`
- `app/build.gradle.kts:9,22-28,32-40,42-48`

---

## 3. native 依赖来源与 ABI 闭包

- 来源：Termux 仓库 bionic 预编译包（`librime 1.17.0-1`、`google-glog 0.7.1-2`、`gflags 2.3.1`、`libyaml-cpp 0.9.0`、`leveldb 1.23-4`、`libsnappy 1.3.1`、`marisa 0.3.1-1`、`libopencc 1.4.2`、`libc++_30`），原始 deb 与解包产物在 `E:\AgentImeTest\rime-deps`。
- `llvm-readelf -d` 校验 NEEDED 闭包：`librime.so` → `libglog, libyaml-cpp, libleveldb, libmarisa, libopencc, libc++_shared, libc, libm, libdl`；`libglog` → `libgflags`；`libleveldb` → `libsnappy`。两个 ABI 各 10 个 `.so` 全部闭合。
- 本项目自编的 `librime_jni.so`（CMake，NDK 27，`c++_shared`）：
  - arm64-v8a：91,984 B，sha256 `CCD685A609459CA257506597FBBBFC4DBEA6700D3C2EAD4335C4FAACF1D1B831`
  - x86_64：90,368 B，sha256 `E1F73E77396032ABC7A8B41A7D29D10392347E235F52236C1445DF9495D21103`

---

## 4. APK 客观证据

```
APK:  ...\project\app\build\outputs\apk\debug\app-debug.apk
大小: 12,821,566 B（clean 重构建于 2026-10-31 19:31:42）
sha256: 09F2981D496123CA279C0591C7C0576A69C6A5F432AD6FA775434F61E3F107B9
applicationId/version: app.agentime 1 / 0.1.0
```

`apkanalyzer files list`（两 ABI 各 11 个 `.so`，此处略去 `/lib/` 目录行）：

```
/lib/arm64-v8a/librime_jni.so      /lib/x86_64/librime_jni.so
/lib/arm64-v8a/librime.so          /lib/x86_64/librime.so
/lib/arm64-v8a/libopencc.so        /lib/x86_64/libopencc.so
/lib/arm64-v8a/libyaml-cpp.so      /lib/x86_64/libyaml-cpp.so
/lib/arm64-v8a/libleveldb.so       /lib/x86_64/libleveldb.so
/lib/arm64-v8a/libsnappy.so        /lib/x86_64/libsnappy.so
/lib/arm64-v8a/libglog.so          /lib/x86_64/libglog.so
/lib/arm64-v8a/libgflags.so        /lib/x86_64/libgflags.so
/lib/arm64-v8a/libgflags_nothreads.so
/lib/arm64-v8a/libmarisa.so        /lib/x86_64/libmarisa.so
/lib/arm64-v8a/libc++_shared.so    /lib/x86_64/libc++_shared.so
```

资产：

```
/assets/rime/default.yaml
/assets/rime/luna_pinyin.schema.yaml
/assets/rime/luna_pinyin.dict.yaml
/assets/rime/essay.txt
/assets/licenses/{librime-BSD-3-Clause, glog-BSD-3-Clause, gflags-BSD-3-Clause,
  yaml-cpp-MIT, leveldb-BSD-3-Clause, snappy-BSD-3-Clause,
  marisa-trie-BSD-2-Clause-or-LGPL-2.1, opencc-Apache-2.0, libcxx-Apache-2.0-LLVM-exception,
  boost-BSL-1.0, rime-prelude-LGPL-3.0, rime-luna-pinyin-LGPL-3.0, rime-essay-LGPL-3.0}.txt
/assets/licenses/NOTICE.md
```

许可证归属：librime/leveldb/glog/gflags/snappy = BSD-3-Clause；yaml-cpp = MIT；opencc/libc++ = Apache-2.0；boost = BSL-1.0；marisa-trie = BSD-2-Clause 或 LGPL-2.1（双许可）；schemas/词库/词频（prelude、luna-pinyin、essay）= **LGPL-3.0**。分发时需满足 LGPL-3.0 的源码/替换性要求，`NOTICE.md` 已逐项列出组件、版本、SPDX、来源与随包文件。

---

## 5. 构建命令与结果

```powershell
. E:\AgentImeTest\env.ps1
cd C:\Users\A'maris\Documents\ChatGPT\agentime
gradle --no-daemon assembleDebug
```

```
BUILD SUCCESSFUL in 15s
45 actionable tasks: 9 executed, 36 up-to-date
退出码 0
```

非阻断告警（保留未改）：`android.overridePathCheck` 为实验性；AGP 8.5.2 只测到 compileSdk 34。

---

## 6. 运行时与交互测试（x86_64 模拟器，Android 35，1080×2340）

设备：`emulator-5554`；启动方式（AVD 在 E 盘，必须设 `ANDROID_AVD_HOME`）：

```powershell
$env:ANDROID_AVD_HOME="E:\AgentImeTest\avd"
emulator.exe -avd AgentIme35 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-save
adb shell ime enable app.agentime/.AgentImeService
adb shell ime set    app.agentime/.AgentImeService
```

### 6.1 引擎启动（原文日志，`E:\AgentImeTest\test-evidence.log`）

```
I RimeData: deployed rime data to /data/user/0/app.agentime/files/rime/shared
I AgentImeRime: rime setup shared=/data/user/0/app.agentime/files/rime/shared user=/data/user/0/app.agentime/files/rime/user
I AgentImeRime: essay.txt loaded: 442688 entries from /data/user/0/app.agentime/files/rime/shared/essay.txt
I AgentImeRime: select_schema(luna_pinyin) = 1
I AgentImeRime: librime ready, version=1.17.0
I RimeEngine: librime initialize=true version=1.17.0
```

→ `isNativeRimeAvailable == true`（loadLibrary + initialize 成功）、`isNativeRimeReady == true`。

### 6.2 交互用例

| # | 用例 | 操作 | 结果（证据） |
|---|---|---|---|
| 1 | 26 键候选 | 点 `n i h a o` | `pinyin 'nihao' -> 9 candidates, first=你好`；候选条 你好/妳好/逆號/擬好/你/拟 |
| 2 | 26 键提交 | 点首个候选 | 宿主框 `http://10.0.2.2:8787` → `http://10你好.0.2.2:8787` |
| 3 | 九键候选 | 切九键，点 `6 4 4 2 6` | `t9 '64426' -> 12 segmentations`；`t9 ranking: 'ni hao' score=21493, 'ni gan' score=1634`；候选 你哈/你好/妳好/逆號/擬好 |
| 4 | 九键提交 | 点第 2 个候选 | 宿主框出现"你好" |
| 5 | 退格 | `nihao` 后点退格 | `pinyin 'niha'`，宿主文本未被删 |
| 6 | 回车 | `niha` 后点回车 | `commit 'niha' -> 'ok'`；宿主框变 `http://10你哈.0.2.2:8787` |
| 7 | Agent 内部输入 | 点 AGENT → 点草稿框 → 输入 `nihao` → 选"你好" | 草稿框显示"你好"；宿主 `editBackend` 仍为 `http://10.0.2.2:8787`（未污染）；面板底部显示 `librime 1.17.0 · 朙月拼音` |
| 8 | 输入框切换 | `editBackend` → `editToken` → 回 `editBackend` | `dumpsys input_method` 的 `mServedView` 依次切换；`editToken` 为 `password="true"`，按设计不显示候选、拒写并 toast「目标已变化，拒绝写入」；切回后候选恢复 |

截图（`E:\AgentImeTest\screens\`）：`06-nihao.png`、`07-commit.png`、`15-t9-64426.png`、`17-t9-freq.png`、`24-agent3.png`、`26-agent-commit.png`、`34-switch-back.png`。

---

## 7. 实现要点（供代码评审）

- `rime_jni.c`：单 `RimeSessionId` + `pthread_mutex` 串行；`initialize(shared,user)` → `setup`/`initialize`/`start_maintenance`/`join_maintenance_thread`/`create_session`/`select_schema("luna_pinyin")`；`pinyinCandidates` 走 `set_input → get_context → menu.candidates[i].text`；`commitPinyin` 走 `select_candidate(0) → get_commit`。
- T9：数字串按 424 音节表 DFS 切分（上限 12 种），逐切分查询 librime 首选词，再用 `essay.txt` 的 FNV 哈希词频重排；无词频时按音节数升序。
- 部署：`RimeData` 把 `assets/rime` 复制到 `filesDir/rime/shared`，用 `shared.version` 戳记；**改资产后必须递增 `RimeData.VERSION`**（当前 "2"），否则不会重新部署。
- 回退：native 不可用/未就绪/抛异常时自动回退 `LimitedChineseInputEngine`，UI 显示「内置拼音词表·回退模式」。

---

## 8. 未完成与已知缺陷（不隐瞒）

1. **九键底部功能行贴边、热区小**（原判“触摸无效”已修正）：`26键 / 0 / 回车 / 退格` 行中心 y≈2133，距屏底 2340 仅 207px，与系统导航栏视觉重叠。用正确坐标 `(959,2133)` 点退格**可生效**（缓冲 `6644`→`664`→`6642`），此前结论源于坐标落在行间隙。建议整行抬高 24–32dp 并加大热区。
2. **Agent 面板横向溢出**：面板内容宽于屏幕，`← 返回普通输入` 左缘被裁、`九键` 右缘被裁（`D1-agent.png`/`D3-agent-commit.png`）；该按钮 adb 点击 `(155,785)` 连续多次无响应，只能靠系统返回键退出——需按父容器宽度重排。
3. **密码框候选抑制不稳定**（新增）：`editToken`（`password="true"`）聚焦时，19:20 不显示候选且 toast「目标已变化，拒绝写入」，19:40 同样聚焦却显示候选条（也/頁/葉/業/夜/野）。抑制时机依赖时序而非 `inputType` 判定，应在 `onStartInputView` 里一次性锁定。
4. **T9 排序是启发式**：多切分并存时依赖 essay 词频 + 音节数，非 librime 原生 T9 权重；实测 `6442` 仍得 `mo ga`（首候选不理想），属于可接受但不完美的启发式。
5. **arm64-v8a 未真机验证**：仅验证打包产物与 x86_64 模拟器运行；Termux bionic 预编译库在 arm64 真机上的加载/部署尚未实测。
6. **CI 未更新**：`.github/workflows/android.yml` 仍是旧构建方式，未切 `./gradlew`，也未处理非 ASCII 路径问题。
7. **非 ASCII 路径**：依赖目录联接绕行，属临时方案；建议长期把工程放在纯 ASCII 路径。

---

## 9. 验收方复现步骤（可直接照跑）

```powershell
# 1) 构建
. E:\AgentImeTest\env.ps1
cd C:\Users\A'maris\Documents\ChatGPT\agentime
gradle --no-daemon clean assembleDebug        # 期望 BUILD SUCCESSFUL

# 2) 校验 APK 内容
$apk="C:\Users\A'maris\Documents\ChatGPT\agent输入法\project\app\build\outputs\apk\debug\app-debug.apk"
& E:\AgentImeTest\sdk\cmdline-tools\latest\bin\apkanalyzer.bat files list $apk |
  Select-String "librime_jni|assets/rime|assets/licenses"

# 3) 校验运行态
adb install -r $apk
adb shell ime enable app.agentime/.AgentImeService
adb shell ime set    app.agentime/.AgentImeService
adb logcat -c; adb shell am start -n app.agentime/.MainActivity
adb logcat -d -s AgentImeRime:* RimeEngine:*
# 期望出现 "librime ready, version=1.17.0" 与 "initialize=true"
```

配套证据文件：`E:\AgentImeTest\rime-build.log`（构建）、`E:\AgentImeTest\test-evidence.log`（APK 清单 + 运行日志）、`E:\AgentImeTest\screens\*.png`（交互截图）。
