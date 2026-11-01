# Agent 输入法 · Trime 基座 · 验收记录（2026-11-01）

结论先行：**在 osfans/trime `develop` 分支上，Agent 输入法闭环的四项要求全部实测通过**（模拟器 AVD `ime35`，Android 15 / API 35 / x86_64）。中文拼音由 rime/librime 原生提供，Agent 面板复用同一块键盘输入问题，回答可插入 / 改写宿主输入框并撤销；密码类输入域禁用 AI。

**第二轮（同日）追加：面板已接真实 DeepSeek**——后端 `project/backend` 升到 0.3，提供 `POST /v1/chat/stream`（SSE 流式 + 断连即中止上游），App 侧 `AgentClient` 流式渲染、可「取消」、超时与错误文案齐备；模拟器实测「中文提问 → 真实流式回答 → 插入宿主 → 取消」全部通过（见 §7）。

## 1. 交付物与可复现信息

| 项 | 值 |
| --- | --- |
| 基座仓库 | `https://github.com/osfans/trime`，分支 `develop`（clone 时 HEAD `7c55dd1`） |
| 本地工作树 | `C:\trimebuild\trime`（**注意：不要放在含中文的路径**，AGP 对非 ASCII 路径直接报错） |
| 本次提交 | `1be1402` `agentime(trime): stream real DeepSeek answers, cancel, deferred input view recovery`（上一提交 `5dfd094` = 面板/写回/敏感域） |
| 产物 APK | `C:\trimebuild\trime\app\build\outputs\apk\debug\com.osfans.trime-1be1402-x86_64-debug.apk`（文件名取自 `git describe`，随提交变化） |
| APK 大小 / SHA-256 | 20,753,374 B / `822028188DC4DDA6DBBC53DE1AB09725ABB213EDD242FD72602A3CE10FA5BF1D` |
| 构建环境 | JDK 17.0.21（Microsoft OpenJDK）、NDK 27.0.12077973、CMake 3.31.6、Gradle 9.8.1（wrapper）、只编 `x86_64` 一个 ABI |
| 构建命令 | 见 §6；`BUILD SUCCESSFUL in ~55s` |

## 2. 功能验收（逐项，附截图）

截图目录：`docs/acceptance-evidence/trime-2026-11-01/`

| # | 验收项 | 操作 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| ① | Agent 入口键 + 面板两态 | 26 键底排 `AI` 键 → 面板「提问态」；点「发送」→「回答态」 | 通过 | `01-panel-pinyin-candidates.png` |
| ① | 面板内中文输入 | 面板打开时直接打 `ni`，候选「弄 農 儂 濃 …」，选「農」落进**面板**（宿主框不变） | 通过 | `01-…png`，日志 `AgentDbg: click send q=農` |
| ② | 插入宿主 | 回答态点「插入」 | 通过：回答写入宿主 EditText，状态「已插入到输入框」 | `02-insert-into-host.png` |
| ② | 改写选区 | 宿主已有选区（`旧的文本` 高亮）→ 面板「插入」执行替换 | 通过：选区被回答替换 | `03-rewrite-selection.png` |
| ② | 撤销（插入） | 点「撤销」 | 通过：插入内容被删除，回到插入前 | `05-undo-insert.png` |
| ② | 撤销（改写） | 改写后点「撤销」 | 通过：写入内容删除并**还原原文**（`旧的文本` 回位） | `06-undo-restore-rewrite.png` |
| ③ | 敏感域禁用 AI | 密码框内打开 AI | 通过：面板显示「敏感输入：已禁用 AI 面板（不读取、不写入该输入框）」，发送/插入/撤销**置灰不可点**，键盘提交不进面板 | `04-sensitive-blocked.png` |
| ③ | 反例：NO_SUGGESTIONS 不误判 | 带 `TYPE_TEXT_FLAG_NO_SUGGESTIONS` 的普通输入框打开 AI | 通过：面板正常可用（这是旧自研壳的 bug，此处不再复现） | `07-no-suggestions-not-sensitive.png` |
| ④ | 中文拼音正常 | 普通输入框打 `ni` / `nihao` | 通过：候选 `弄 農 儂 濃 …`，schema `朙月拼音 (luna_pinyin)` | 见 §3 日志 |

## 3. 关键日志证据（logcat）

```
I/rime.trime: dictionary 'luna_pinyin' is ready.
I/rime.trime: finished updating schemas: 3 success, 0 failure
I/AgentDbg: dispatch action=0 at 680.0,1998.0 handled=true      # AI 键命中
I/AgentDbg: shownRect=Rect(0, 535 - 1080, 1275) h=740           # 面板尺寸
I/AgentDbg: click send q=農                                      # 面板输入 → 提问
I/AgentDbg: click insert answer=收到问题：「農」…                 # 写入宿主
I/AgentDbg: undo len=43 restore=0                                # 撤销（插入）
I/AgentDbg: undo len=42 restore=6                                # 撤销（改写，还原 6 字原文）
```

## 4. 代码改动清单

新增：

- `app/src/main/java/com/osfans/trime/ime/agent/AgentPanel.kt` — 纯代码构建的悬浮面板（标题/问题/回答/状态/按钮行），提供 `appendText/backspace/setAnswer/setStatus/enterBlockedMode/setInsertLabel`；第二轮增加流式态（`beginStreaming/appendAnswer/endStreaming`、`取消` 按钮、「清空」在生成中先取消）。
- `app/src/main/java/com/osfans/trime/ime/agent/AgentClient.kt` — SSE 流式客户端：`ask(question, onDelta, onDone, onError)`，连接 10s / 读 45s 超时，`cancel()` 断连即中止，错误映射为中文文案（令牌无效/额度用完/未配 Key/上游报错/超时/后端未启动）。不打印正文日志。
- `app/src/main/java/com/osfans/trime/ui/dev/AgentTestActivity.kt` — 调试宿主（普通域 / NO_SUGGESTIONS 域 / 密码域 / 改写域），不是产品功能。

修改：

- `ime/core/InputView.kt`：面板挂载与 `bringToFront()`、`agentPanelTopInWindow()`、`showAgentPanel()/hideAgentPanel()/toggleAgentPanel()`、`submitAgentQuestion()/insertAgentAnswer()/undoAgentAnswer()`；打开面板前 `service.captureHostSelection()`。
- `ime/core/TrimeInputMethodService.kt`：`panelTextSink/panelBackspaceSink/panelSubmitSink`（面板打开时上屏文本改道面板）、`isSensitiveInput`、`captureHostSelection/hostHadSelection`、`writeToHost/undoAgentInsert`、`toggleAgentPanel`。
- `ime/keyboard/CommonKeyboardActionListener.kt`：面板打开时按键分流（Enter→发送、退格→面板退格、上屏文本→面板），新增 `agent_window` 命令分支调 `service.toggleAgentPanel()`。
- `assets/shared/trime.yaml`：`preset_keys` 增加 `AGENT`（`send: FUNCTION, command: agent_window`），`preset_keyboards.qwerty` 底排 `space` 30→25 后插入 `{click: AGENT, width: 5}`。
- `AndroidManifest.xml`：注册调试宿主 Activity。

## 5. 同时修掉的三个上游级问题（不修则功能不可用）

1. **`assets/shared` 下的 14 个 rime 数据文件在 git 里是符号链接**，指向 `app/data/rime/…`。Windows 未开 Developer Mode 时 clone 得到的是指针文本（内容为 `../../../../data/rime/…`，42–65 B），打包进 APK 后 **rime deploy 永远失败**，表现为键盘空格键显示 `.default`、`schemaId=.default`、拼音无候选。
   → 修复：从 `app/data/rime/` 把真实文件拷回 `assets/shared/`（essay.txt 6,330,504 B、luna_pinyin.dict.yaml 962,089 B、stroke.dict.yaml 2,330,785 B 等）。
2. **`onCreateInputView()` 在 rime 未就绪时返回 `null` 后永不重试**：`ThemeScope is not ready yet; deferring input view creation` → IME 窗口 `surface=[0,0][0,0]`，`mInputShown=true` 但键盘空白（冷启动/装完首次弹键盘必现）。
   → 修复（第一版用 `rime.runOnReady` 补建，但实测在「装完首次启动」时该回调可能不落地，仍空白）：改为 **Handler 轮询** `ensureDeferredInputView()`，每 300ms 检查 `themeScope`，就绪即 `replaceInputViews()` 并按需 `updateInputViewShown()`，最多等 30s；日志 `agentime: creating deferred input view after N ms`。
3. **面板浮在键盘上方时不在 IME 窗口可触摸区**：`onComputeInsets` 原本 `contentTopInsets=visibleTopInsets=键盘顶部` + `TOUCHABLE_INSETS_VISIBLE`，面板区域的点击被系统透传给背后的 App，按钮完全点不动。
   → 修复：面板可见时改用 `Insets.TOUCHABLE_INSETS_REGION`，`touchableRegion` 向上扩展到面板顶部；`contentTopInsets` 仍按键盘顶部上报（宿主布局不跳动）。

另有环境侧两处必要操作（非代码）：

- 首次启动前必须把 `profile_data_storage_mode` 设为 `APP_STORAGE`，否则 `Rime.kt: Skip starting rime: storage not available!`，键盘永远空白（本机模拟器已写入该 prefs）。
- 不要用 `adb shell am force-stop <IME 包>`：会把系统默认输入法重置回 LatinIME。要重启 IME 用 `adb shell kill -9 <pid>`。

## 6. 复现步骤

```powershell
# 1) 准备（一次性）
& E:\AgentImeTest\sdk\cmdline-tools\latest\bin\sdkmanager.bat --proxy=http --proxy_host=127.0.0.1 --proxy_port=7897 ^
  "platforms;android-37.0" "build-tools;37.0.0" "ndk;27.0.12077973" "cmake;3.31.6"

# 2) 构建（PATH 需含 git 与 python3）
. E:\AgentImeTest\env.ps1
$env:NDK_VERSION="27.0.12077973"; $env:CMAKE_VERSION="3.31.6"; $env:BUILD_ABI="x86_64"
$env:JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897"
cd C:\trimebuild\trime
.\gradlew.bat --no-daemon assembleDebug

# 3) 安装与启用
adb -s emulator-5556 install -r -g app\build\outputs\apk\debug\com.osfans.trime-7c55dd1-x86_64-debug.apk
adb -s emulator-5556 shell ime enable com.osfans.trime.debug/com.osfans.trime.ime.core.TrimeInputMethodService
adb -s emulator-5556 shell ime set   com.osfans.trime.debug/com.osfans.trime.ime.core.TrimeInputMethodService

# 4) 打开调试宿主
adb -s emulator-5556 shell am start -n com.osfans.trime.debug/com.osfans.trime.ui.dev.AgentTestActivity
```

## 7. 接入真实 DeepSeek（后端 0.3 + 客户端流式，第二轮）

### 7.1 架构与安全边界

- **Key 只在 PC 上**：`project/backend` 是持 Key 的标准库 Python 代理；APK 内不含任何 Key（反编译也拿不到）。
- App → 后端：`http://127.0.0.1:8787/v1/chat/stream`，模拟器通过 `adb reverse tcp:8787 tcp:8787` 打通；真机改局域网 IP。
- 后端 → 上游：`https://api.deepseek.com/v1/chat/completions`，模型 `deepseek-chat`，`stream: true`。
- 客户端令牌：`CLIENT_TOKEN`（默认 `dev-agentime-token`），仅作共享密钥，无多用户隔离。

### 7.2 后端 0.3（`project/backend/app.py`）

- 新增 `POST /v1/chat/stream`：`text/event-stream`，逐帧 `data: {"choices":[{"delta":{"content":"…"}}]}`，结束 `data: {"usage":{…},"done":true}` + `data: [DONE]`；异常下发 `data: {"error":…}`。
- **客户端断开即中止上游生成**：写 SSE 抛异常时立刻 `resp.close()`，metrics 记 `stream_client_cancelled`。
- 上游请求走自建 `ProxyHandler({})`，避免继承本机失效的 `HTTP(S)_PROXY` 环境变量导致超时。
- `MOCK_UPSTREAM=1` 可离线逐字合成流，便于无 Key 演示。
- 启动：`project/backend/run-backend.ps1`（自动从 User 作用域读 `DEEPSEEK_API_KEY`；`-Mock` 走离线模式）。
- host 侧实测：`/healthz` → `{"ok": true}`；`/v1/chat` → 真实回答 + `usage{in:43,out:20}`；`/v1/chat/stream` → 真实增量帧。

### 7.3 App 侧流式与取消

- 面板「发送」→ `beginStreaming("正在请求…")`：发送置灰、右侧出现「取消」、状态行显示进度。
- 增量回调追加到回答区并自动滚到底；结束状态「已生成，可点「插入」写入输入框」。
- 「取消」→ `connection.disconnect()`，面板显示「已取消」，已收到的片段保留。
- 错误文案：`401 客户端令牌无效` / `429 今天额度已用完` / `500 后端未配置上游 Key` / `502 上游模型报错` / `504 上游超时` / 连接失败「后端没有启动/地址不对」。

### 7.4 实测证据

| 验收项 | 结果 | 证据 |
| --- | --- | --- |
| 真实流式回答 | 面板问「莫」，1.5s 内出现真实作答：「「莫」在中文里常表示“不要、没有、不”，如“莫慌”“莫大”“莫非”。也可作姓氏。…」 | `08-deepseek-stream-answer.png` |
| 插入宿主 | 点「插入」→ 宿主 EditText 写入该段文字，状态「已插入到输入框」 | `09-insert-deepseek-answer.png` |
| 流式中间态 | 发送置灰、「取消」出现、状态「正在请求…」 | `10-streaming-cancel-available.png` |
| 取消（UI） | 点「取消」→ 状态「已取消」，回答停止追加，按钮恢复；客户端日志 `click cancel` | `11-cancel-done.png` |
| 取消（后端中止上游） | 直连 SSE 客户端读 62 帧后断开 → metrics `stream_client_cancelled` 1→2、`stream_ok` 不变 | 见 7.5 |
| 空白键盘复原（上游缺陷 2 的回归） | 重装后首次弹键盘 997ms 内自动补建，日志 `agentime: creating deferred input view after 997 ms` | `12-deferred-input-view-recovered.png` |

### 7.5 后端指标（实测输出）

```text
# 正常两条流式 + 一次客户端中止后
{"requests_ok":1,"tokens_in":43,"tokens_out":20,
 "bad_json":1,"stream_client_cancelled":2,"stream_ok":3}
```

## 8. 已知限制（未做/待做）

1. **Agent 已接真实上游（DeepSeek）**，但依赖本机后端代理启动；`DEEPSEEK_API_KEY` 目前放在 PC 的 User 环境变量里，设备端不保存。
2. 模拟器包为 `x86_64`；**真机包 `arm64-v8a` 已产出**（见 §9），真机实测由用户执行，尚未回收结果。
3. 改写/撤销以「面板打开时的选区」为快照，若期间用户在宿主里手动改了文本，撤销只按长度删，可能不精确（后续可换成 `beginBatchEdit` + 全文本快照）。
4. 面板高度固定（回答区 140dp），长回答滚动查看；九宫格键盘尚未做（当前为 26 键 QWERTY + 中文拼音）。
5. 桌面端与手机若不在同一 Wi-Fi（或防火墙未放行 8787），面板会报「后端没有启动/地址不对」；提供 `adb reverse` 备选路径。
6. 未做 TalkBack / 大字体适配。

## 9. 真机交付包（2026-11-01 增补）

为让用户真机自测，本轮做了四项改动后重编 `arm64-v8a`：

| 改动 | 位置 | 目的 |
| --- | --- | --- |
| 存储模式默认 `APP_STORAGE` | `data/prefs/AppPrefs.kt:378` | 真机首次安装不再因 `storage not available` 导致 rime 不启动、键盘空白 |
| 面板内可配置后端地址 / 令牌 | `ime/agent/AgentConfig.kt`（新）+ `AgentPanel.kt` 设置区 + `InputView.kt` 回调 | 用户换电脑 IP / 令牌时不必重编 APK |
| 设置态键盘输入原样 ASCII 直通 | `CommonKeyboardActionListener.handlePanelKey` + `TrimeInputMethodService.isPanelSettingsOpen()` | 避免 URL 被拼音组合吃掉；验证：设置里打 `ab` → 地址行末尾出现 `ab` |
| 面板背景改为不透明 | `AgentPanel.panelBackground()` | 半透明会透出下层 preedit 边框，出现「问题行被黑框包住」的假故障 |

交付物：

| 项目 | 值 |
| --- | --- |
| APK | `C:\Users\A'maris\Documents\ChatGPT\agent输入法\dist\AgentIME-trime-arm64-v8a-debug.apk` |
| 大小 / SHA-256 | 20,713,986 B / `13AED62ACE676D5AD6A0AD4E2A31FFD2F81E2043BDF0872F831A4F55D815CF64` |
| 包名 / 版本 / ABI | `com.osfans.trime.debug` / 3.3.13 (20261101) / 仅 `arm64-v8a` |
| 源码提交 | `C:\trimebuild\trime` @ `d056ce8` |

x86_64 回归（模拟器 AVD `ime35`，本轮实测）：

| 验收项 | 结果 | 证据 |
| --- | --- | --- |
| 设置页打开 | 点「设置」→ 显示后端地址（`http://127.0.0.1:8787/v1/chat/stream`）、令牌、保存/返回问答 | `14-panel-settings.png` |
| 设置保存 | 点「保存」→ 状态「已保存后端设置」，prefs `agentime_config.xml` 落盘 `endpoint` / `token` 两项 | `15-settings-saved.png` + `run-as ... cat shared_prefs/agentime_config.xml` |
| 设置态 ASCII 直通 | 选中地址行后按 `a`、`b` → 地址行末追加 `ab`（无拼音候选） | `16-settings-ascii-input.png` |
| 配置生效（流式回归） | 把 prefs endpoint 改为 `http://127.0.0.1:8787/v1/chat/stream` → 面板问「莫」→ 流式回答完成；后端 metrics `stream_ok` 0→1 | 后端 metrics + `17-config-streaming-ok.png` |
| 面板背景 | 不透明后面板内不再出现黑色描边框 | `18-panel-opaque.png` |

说明：真机包（arm64）**未在真机上运行过**——本机只有 x86_64 模拟器，arm64 包已核对 `aapt dump badging`（`native-code: 'arm64-v8a'`、minSdk 21、targetSdk 37），实际冒烟由用户按《Agent输入法-真机安装与联调-2026-11-01.md》执行。

## 10. 参考

- Trime 上游：<https://github.com/osfans/trime>（develop，commit `7c55dd1` 基线）
- 构建脚本：`C:\trimebuild\build-trime.ps1`（环境变量 `NDK_VERSION=27.0.12077973`、`CMAKE_VERSION=3.31.6`、`BUILD_ABI=x86_64|arm64-v8a`）
- 真机联调说明：同目录 `Agent输入法-真机安装与联调-2026-11-01.md`

