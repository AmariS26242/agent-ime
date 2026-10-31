# Agent 输入法最终返工验收（single / final）

- 日期：2026-10-31
- 设备：emulator-5554，1080x2340 / density 440（Android 15 / API 35）
- 构建：`gradle --no-daemon clean assembleDebug`（junction `C:\Users\A'maris\Documents\ChatGPT\agentime` → 本项目）
- 最终 APK：`app/build/outputs/apk/debug/app-debug.apk`，12,825,622 字节
- **最终 SHA-256：`25B082E0813A12529178E2D063F13EDE13813507F284FB4BC066025F080BF491`**

## 1. 结论

缺陷已修复并复验通过：**首次**弹出普通键盘即为四排完整，底排键面 bottom = **2175**，导航栏顶 = **2208**，留白 **33px**；首次与“Agent → 切回普通输入”后一致。九键底排 2177（31px）、敏感直输/敏感 Agent/候选恢复均为 2175（33px），窄屏 720x1280/320 普通 1159（25px）、Agent 1175（9px）。所有场景底排 bottom ≤ 2208，内容不再画入导航栏。

## 2. 根因

`onCreateInputView()` 原先只在 `setOnApplyWindowInsetsListener` 里把 `navBars.bottom` 设为容器 bottom padding，并在末尾 `requestApplyInsets(container)`。但容器创建时尚未 attach，首次布局的窗口高度在导航栏 inset 真正到达前就已确定：

1. 创建时 `currentWindowMetrics` 已经包含 nav bottom = 132，但代码没有使用；
2. 首次 insets 分发先报 `navBars.bottom = 0`（`pad=0`，view size 仍 0x0）；
3. 第二次分发报 `132`，`setPadding(...,132)`，此时 view size 仍是 0x0；
4. IME 窗口随后按“无 padding 的内容高度”锁定为 **758px**（`Requested w=1080 h=758`，frame `[0,1582][1080,2340]`）；post 时实测 `pad=132 size=1080x758` —— padding 被加在固定 758px 的窗口**内部**，内容区反而被压缩，第 4 排被挤出窗口底、落到导航栏下面。
5. 模式切换触发一次新的窗口 relayout，窗口高度重算为 890，padding 才“看起来生效”——这正是“切换后恢复”的原因。

插桩日志（临时 diag 构建，已删除，不作为交付代码）：

```
create bounds=Rect(0, 0 - 1080, 2340) nav=Insets{left=0, top=0, right=0, bottom=132}
listener BEFORE pad=0 size=0x0 navB=0
listener AFTER  pad=0
listener BEFORE pad=0 size=0x0 navB=132
listener AFTER  pad=132
post pad=132 size=1080x758
```

基线窗口证据：`Requested w=1080 h=758`、`Frames: frame=[0,1582][1080,2340]`、`touchable region=(0,1582,1080,2340)`；修复后首次即 `Requested w=1080 h=890`、`frame=[0,1450][1080,2340]`。

## 3. 修改（唯一代码文件）

`app/src/main/java/app/agentime/AgentImeService.kt`（共 1339 行）：

- **:91-106**：新增注释与同步读取导航栏高度：
  ```kotlin
  val initialNavBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      getSystemService(WindowManager::class.java)
          .currentWindowMetrics
          .windowInsets
          .getInsets(android.view.WindowInsets.Type.navigationBars())
          .bottom
  } else {
      0
  }
  ```
- **:107**：`container.setPadding(0, 0, 0, initialNavBottom)` —— 在 view attach / 首次 measure **之前**预留导航栏空间，使首次 measure 就是 758+132=890，窗口首次请求高度即正确。
- **:109-118**：insets listener 增加 `if (navBars.bottom > 0 || v.isLaidOut)` 守卫，跳过 pre-layout 的瞬时 0 分发；布局完成后以真实 inset 为准，仍覆盖旋转、导航模式切换。
- **:119**：保留 `requestApplyInsets(container)`。

未修改 layout、`Keypad.kt`、后端协议、隐私边界、宿主文本读写；未 reset、未删除 Rime 资源。仅 `AgentImeService.kt` 有代码改动。

## 4. 构建

- 环境：`. E:\AgentImeTest\env.ps1`（JDK 17.0.21 / Android SDK 35 / Gradle 8.11.1 / CMake 3.31.6）
- 命令（独占，无并发 gradle/java worker，仅常驻 adb）：
  `gradle --no-daemon clean assembleDebug`
- 日志：`docs/acceptance-evidence/build-final-single.log`
- 结果：**BUILD SUCCESSFUL in 41s**（48 actionable tasks，47 executed）
- 仅既有 Kotlin 警告：`Keypad.kt:154` 未用变量、`Network.kt:56/69` 参数名；无新增错误/警告。

## 5. 安装与安装包一致性

安装顺序（避免 `ime set` 后 force-stop 回退 LatinIME）：

```
adb install -r app-debug.apk           # Success
adb shell am force-stop app.agentime
adb shell ime set app.agentime/.AgentImeService
# 校验 default_input_method = app.agentime/.AgentImeService
adb shell am start -n app.agentime/.MainActivity
```

- `versionName=0.1.0`，`lastUpdateTime=2026-10-31 22:55:14`
- `pm path app.agentime`：`/data/app/~~35F5Yhc-uwly9VknGKYNBQ==/app.agentime-ZV97I7hM9w6TJ_qB3vHLZA==/base.apk`
- `adb pull` 回本机：`docs/acceptance-evidence/final-single-installed-base.apk`，12,825,622 字节，SHA-256 与构建产物**完全一致**（`25B082E0…BF491`）。

## 6. 证据清单（均针对最终 SHA）

| 截图 | 场景 | 导航栏顶 | 底排键面 bottom | 留白 |
|---|---|---|---|---|
| `final-single-01-first-normal.png` | 冷启动首次普通键盘 | 2208 | 2175 | 33px |
| `final-single-02-agent-top.png` | Agent 顶部/面板 | 2208 | 2197（askKeypad qwerty） | 11px |
| `final-single-03-ninekey-bottom.png` | Agent 九键底排 | 2208 | 2177 | 31px |
| `final-single-04-sensitive-direct.png` | 敏感无候选直输 | 2208 | 2175 | 33px |
| `final-single-05-sensitive-agent.png` | 敏感 Agent 面板 | 2208 | 2175 | 33px |
| `final-single-06-normal-candidates.png` | 切回普通 + 候选 | 2208 | 2175 | 33px |
| `final-single-07-narrow-agent.png` | 窄屏 720x1280 Agent | 1184 | 1175 | 9px |
| `final-single-08-narrow-normal.png` | 窄屏普通键盘 | 1184 | 1159 | 25px |

配套文件：

- 窗口：`final-single-window-first.txt`（`Requested 1080x890`，`frame=[0,1450][1080,2340]`）、`final-single-window-ninekey.txt`（`Requested 1080x1683`，`frame=[0,657][1080,2340]`）
- UI dump：`final-single-ui-first.xml`（editBackend focused=true）、`final-single-ui-token.xml`、`final-single-ui-sensitive.xml`（editToken password=true focused=true）、`final-single-ui-backend.xml`、`final-single-ui-narrow.xml`
- 像素测量：`final-single-measurements.txt`（脚本 `measure_ime.py`，nav 色 (136,138,143)，键面白 ≥250）
- 安装包清单：`final-single-apkanalyzer.txt`（过滤节选）、`final-single-apkanalyzer-full.txt`（全部 528 条）
- 窄屏采集后已 `wm size reset` / `wm density reset`，设备恢复 1080x2340、density 440，`navigationBars frame=[0,2208][1080,2340]`。

## 7. APK 内容校验（apkanalyzer）

`apkanalyzer files list app-debug.apk`：

- 双 ABI：`/lib/arm64-v8a/librime_jni.so`、`/lib/x86_64/librime_jni.so`
- `/assets/rime/`：`luna_pinyin.schema.yaml`、`luna_pinyin.dict.yaml`、`essay.txt`、`default.yaml`（+ 目录条目）
- `/assets/licenses/`：yaml-cpp-MIT、snappy-BSD-3、rime-prelude/luna-pinyin/essay-LGPL-3.0、opencc-Apache-2.0、marisa、librime-BSD-3、libcxx、leveldb、glog、gflags、boost、NOTICE.md（+ 目录条目）
- APK 总条目 528

## 8. 剩余问题 / 风险

1. **Agent qwerty 底排留白偏紧**：1080 下 11px、窄屏 9px，虽满足“≥8px”，但明显小于普通键盘 33px。原因：agent `askKeypad` 底排容器内部 bottom padding 小于 `keyboard_normal.xml` `rowBottom` 的 8dp+2dp。不影响遮挡结论；若要求视觉一致可在 `keyboard_agent.xml` / `Keypad.kt` 另做（本次为控制改动面未动）。
2. **API < 30 路径**：`currentWindowMetrics` 不可用时 `initialNavBottom=0`，仍依赖 listener，理论上首帧可能复现同一时序问题。本缺陷运行平台为 API 35 edge-to-edge，已在目标设备验证；未在 API 26-29 真机/模拟器验证。
3. **敏感态九键开关**：切九键仍无效果且无提示（既有设计，本次未改，不属本次缺陷）。
4. **模拟器稳定性**：此前有自重启/被并发重装史；本轮构建与采集独占，未与其他 gradle/adb worker 混用证据。
