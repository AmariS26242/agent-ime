# Agent 输入法 UI 定向复核与修正（2026-10-31 22:15）

本轮只改 `app/src/main/java/app/agentime/AgentImeService.kt`（1316 行，SHA-256
`4FB5658682736BD8E497430650F041CB4AAEFDF168B8A75FC74EA57FB0EF68BE`）。
未动 Rime native、后端协议、宿主文本读写逻辑、布局 XML 与配色。

## 1. 修正点

### 1.1 敏感宿主下 Agent 面板状态标签必须可见（:657-668）

```kotlin
fun rebuildAskKeypad() {
    // A sensitive host also keeps the internal draft on plain direct input:
    // nothing is held in a pinyin/nine-key buffer that could be silently lost
    // while the candidate row is suppressed.
    if (hostSensitive) {
        askNineKey = false
        askPinyin = ""
        askNineBuffer = ""
    }
    // The engine caption is status, not a candidate row: it must stay visible
    // even in a sensitive host where only the candidate row is suppressed.
    askCaption.visibility = View.VISIBLE
```

修正前是 `askCaption.visibility = if (hostSensitive) View.GONE else View.VISIBLE`，
与“只隐藏候选行、状态标签保留”的要求相反。现在标签恒为 VISIBLE，候选行仍由
`refreshAskCandidates()`（:631-636）在敏感模式下清空并 `View.GONE`；同时敏感模式会
清掉 `askPinyin` / `askNineBuffer` / `askNineKey`，避免隐藏缓冲。

### 1.2 rebuildAgentKeypad / clearAgentComposition 闭包赋值（:762-775）

```kotlin
    // Expose the local rebuild/reset routines before the initial keypad build,
    // so onStartInputView can rebuild or clear the agent composition whenever
    // the host editor changes and no stale pinyin / nine-key buffer survives.
    rebuildAgentKeypad = { rebuildAskKeypad() }
    clearAgentComposition = {
        askPinyin = ""
        askNineBuffer = ""
        askNineKey = false
        askCandidateRow.removeAllViews()
        askCandidateRow.visibility = View.GONE
        view.findViewById<Button>(R.id.btnNineKeyAgent).setText(R.string.toolbar_nine_key)
    }
    rebuildAskKeypad()
```

此前 `rebuildAgentKeypad`（:48）与 `clearAgentComposition`（:49）只有 `?.invoke()`
调用（:138 的 `onStartInputView`、:236 的 `clearCompositionUi`、:251），全文件没有任何
赋值，两个闭包恒为 null —— 切换编辑器时的 Agent 清理与重建实际是空操作。

`onStartInputView`（:121-145）现在依次执行 `clearCompositionUi()`（:136，含
`clearAgentComposition?.invoke()`）→ `renderNormalKeypad()`（:137）→
`rebuildAgentKeypad?.invoke()`（:138）→ `applyMode()`（:144），因此：

- 编辑器切换时 Agent 拼音/九键缓冲被清空，候选行清空并隐藏；
- 当前键区按新的 `hostSensitive` 重建（敏感 → `buildDirect`，:726-734）；
- 不读取宿主正文：`isSensitive(EditorInfo)`（:214）只看 `inputType` / `imeOptions`。

### 1.3 敏感宿主下 Agent 九键入口不积累隐藏缓冲（:743-753）

```kotlin
view.findViewById<Button>(R.id.btnNineKeyAgent).setOnClickListener {
    // No nine-key toggle while the host is sensitive: keep the draft on
    // direct input instead of collecting an invisible digit buffer.
    if (hostSensitive) {
        askNineKey = false
        askNineBuffer = ""
        askPinyin = ""
        view.findViewById<Button>(R.id.btnNineKeyAgent).setText(R.string.toolbar_nine_key)
        rebuildAskKeypad()
        return@setOnClickListener
    }
```

## 2. 构建

`gradle --no-daemon clean assembleDebug`（cwd 必须是 ASCII junction
`C:\Users\A'maris\Documents\ChatGPT\agentime`；中文路径会让 CMake 收到 mojibake 并
0xC0000409 崩溃）：

- BUILD SUCCESSFUL in 45s，48 actionable tasks（47 executed, 1 up-to-date）；
- arm64-v8a 与 x86_64 两个 ABI 的 configureCMake/buildCMake 均通过；
- 仅有既有 Kotlin 警告：`Keypad.kt:154` 'idx' unused、`Network.kt:56/69` Callback 参数名。

日志：`build-clean-assembleDebug-2026-10-31-2211.log`（另存为规范名
`build-clean-assembleDebug-2026-10-31.log`）。

## 3. APK

| 产物 | size | mtime | SHA-256 |
| --- | --- | --- | --- |
| 我这次 clean 构建 | 12825390 | 22:11:52 | `9830841AD7CD8969561B72E24C4713C58CA72A19674B2FFE09F9EF6094BA4FF2` |
| 设备上实际安装（22:12:48 由并行任务重建，源码 SHA 相同） | 12825382 | 22:12:48 | `358C5DFD80BF20BBAF36A1AA00CFD36362DEAC6D0F78F9A049E6E23087548110` |

设备（emulator-5554，1080x2340，Android 15）上 `lastUpdateTime=2026-10-31 22:13:01`，
`versionName=0.1.0`；用 `pm path` 拉回 base.apk 复核，SHA-256 与 22:12:48 产物一致。
截图均拍自该已安装版本。

## 4. 截图证据（1080x2340）

| 文件 | 证明什么 |
| --- | --- |
| `after-fix-01-sensitive-normal.png` | 敏感宿主（token 密码框）主键盘 direct：QWERTY 无「汉/拼音/取消拼音」，无候选行 |
| `after-fix-02-sensitive-agent-panel.png` | 敏感宿主 + Agent 面板：「敏感输入，已禁用 AI 上下文」、状态标签 `librime 1.17.0 · 朙月拼音` **可见**、标签下无候选行、键区 direct |
| `after-fix-03-sensitive-ninekey-blocked.png` | 敏感模式下点「九键」：面板与 direct 键区不变，未进入九键、未积累隐藏数字缓冲 |
| `after-fix-04-normal-candidates-recovered.png` | 切回非敏感输入框（后端地址）：候选行恢复（你呢拟尼泥妳），「汉/拼音/取消拼音」回到键区 |
| `after-fix-00-launch.png` | 安装后首次启动的宿主页面 |

## 5. APK 内容校验

`apkanalyzer files list` → `apk-files-list-2026-10-31-2201.txt`（528 行）。
条目 SHA-256 明细见 `apk-verify-2026-10-31-2211.txt`，要点：

- `/lib/arm64-v8a/librime_jni.so` — 30400 B — `852EDEFBC1501844F56D1C859C515FF59739B7F47A087FF82ECC9D852521EDF2`
- `/lib/x86_64/librime_jni.so` — 31096 B — `C06DF837E6D760B1BBA7F6F3FE348FE96DC1C4742D4AA020AB3982C17BD2F3E5`
- `assets/rime/`：`default.yaml`、`essay.txt`(5887319 B)、`luna_pinyin.dict.yaml`(889896 B)、`luna_pinyin.schema.yaml`
- `assets/licenses/`：13 个许可文件 + `NOTICE.md`，共 14 个

## 6. 遗留问题

1. 并行任务同一工作区改同一文件/重建 APK：22:08:38 源码被改写（我的两处修正仍在，闭包赋值位置前移），22:12:48 又产出新 APK。行号以源码 SHA `4FB56586…` 为准，后续改动会让本节行号漂移。
2. 模拟器不稳定：两次 VM 挂死（`device offline` / emulator 退出），一次 `uiautomator dump` 自身崩溃留下过期 XML，导致按旧 bounds 点击会点错输入框；最终证据是在重启后逐张截图校验得到的。
3. 宿主「选择输入法」按钮容易误触，会把默认输入法切成 AOSP LatinIME；需 `adb shell ime set app.agentime/.AgentImeService` 复位。
4. 测试过程中在宿主「后端地址」输入框残留过 `bu` 等测试字符，未清理（未写成正式验收状态）。
5. 未验证真实物理键盘/第三方宿主的 `IME_FLAG_NO_PERSONALIZED_LEARNING` 场景，仅用 `textPassword` 字段模拟敏感宿主。
