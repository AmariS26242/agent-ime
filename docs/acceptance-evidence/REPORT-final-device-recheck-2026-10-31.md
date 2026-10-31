# 最终 APK 设备复验报告（emulator-5554，1080x2340 / 440dpi）

- 时间：2026-10-31 22:06 – 22:22（Asia/Shanghai）
- 只做设备复验，**未修改任何源码**
- adb：`E:\AgentImeTest\sdk\platform-tools\adb.exe`（系统 PATH 无 adb），所有命令带 `-s emulator-5554`

## 1. 安装与 IME 恢复

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| 校验哈希 | `Get-FileHash app-debug.apk -Algorithm SHA256` | `96682664BB3473937B5A03C31E6FECCEA970B6AACE30944772F4097B8924902A`（与要求一致），12825362 字节 |
| 安装 | `adb -s emulator-5554 install -r <apk>` | `Performing Streamed Install` / **`Success`** |
| 安装生效 | `adb shell dumpsys package app.agentime` | `versionName=0.1.0`，`lastUpdateTime=2026-10-31 22:07:06` |
| 恢复 IME | `adb shell ime set app.agentime/.AgentImeService` | `Input method app.agentime/.AgentImeService selected for user #0` |
| 默认输入法 | `adb shell settings get secure default_input_method` | `app.agentime/.AgentImeService` |
| IME 接管 | `adb shell dumpsys input_method` | `mCurMethodId=app.agentime/.AgentImeService`、`mInputShown=true`、`mServedView=.../app:id/editBackend` |

**安装成功（两次），但构建产物在复验过程中被改写了 —— 见下节。**

### 1.1 ⚠️ 哈希不一致：任务指定的 APK 已被重新构建

| 时间 | 事实 |
| --- | --- |
| 22:05:16 | `app-debug.apk` = 12825362 字节，SHA-256 **`96682664BB…4902A`**（= 任务要求值） |
| 22:07:06 | 我 `install -r` 成功；`dumpsys package` 的 `lastUpdateTime=22:07:06` 与本次安装对应 |
| 22:12 | **APK 被重新构建**：现为 12825382 字节，SHA-256 **`358C5DFD80BF20BBAF36A1AA00CFD36362DEAC6D0F78F9A049E6E23087548110`** |
| 22:13:01 | 非本会话的 `installPackageLI` 把设备上的 App 换成该新产物（`Force stopping app.agentime` + `lastUpdateTime=22:13:01`） |
| 22:21:57 | 我按当前产物再装一次并核对：`install -r` → `Success`；`pm path` 拉回 base.apk 计算 SHA-256 = `358C5DFD…8110`，与磁盘文件**逐位一致** |

→ 结论：设备上现在装的是 **`358C5DFD…8110`（22:12 新构建）**，且已用"拉回安装包再算哈希"验证一致。
→ 任务要求的 `96682664…` 对应的是 22:05 那次构建，已被覆盖，仓库内已无该文件副本（`Get-ChildItem -Recurse *.apk` 只剩新的 12825382 字节版本与一份 `tmp-installed-base.apk`）。
→ **需要父会话确认哪份产物为准**：若必须是 `96682664…`，需从 CI/构建缓存取回该二进制再重跑本报告的采集脚本；本报告的截图全部基于设备上实际安装的那份。

## 2. 五项验收证据

### 项 1 · Agent 面板顶部 / 状态标签 / 返回按钮
- 截图：`final-09-agent-panel-verified-build.png`（22:22，安装在设备的 `358C5DFD…` 构建上）；同内容旧构建版 `v2-agent-panel-a.png`（22:11，`96682664…`）
- 操作：普通框聚焦后 `adb shell input tap 140 1647`（AGENT 工具条按钮；该帧工具条 y≈1590–1705）
- 实拍内容：顶部单行三按钮 **`返回普通输入` / `会话` / `九键`**（单行、autoSize 生效，无换行无截断）；其下状态标签 **`输入到 Agent`**；面板内 `未附上下文`、`加选区/加草稿/粘贴/展开全文`、草稿框 hint `输入你的问题，只发送到 Agent`、`帮我改` + `提问`；底部状态行 `librime 1.17.0 · 朗月拼音`
- 顶部三按钮水平边界（y=805 行像素扫描）：`返回普通输入` x14–451（中心 232）、`会话` x469–758（中心 613）、`九键` x776–1065（中心 920）
- 返回按钮验证：`adb shell input tap 232 805` → 回到普通键盘，证据 `v2-back-to-normal.png`

### 项 2 · 九键底部行 `26键 / 0 / 回车 / 退格` 与安全底距
- 截图：`final-07-ninekey-bottom.png`
- 操作：Agent 面板内 `adb shell input tap 920 805`（工具条 `九键`）→ 键位切换为九宫格，工具条标签变为 `26键`
- 实拍：九宫格 `1 / 2ABC / 3DEF / 4GHI / 5JKL / 6MNO / 7PQRS / 8TUV / 9WXYZ`，底排 **`26键` `0` `回车` `退格`** 完整可见
- 安全底距（像素实测）：底排键面底边 **y=2177**，导航栏/Taskbar 顶边 **y=2208** → **留 31px（≈11dp）净空，无遮挡**
- 同一帧 `dumpsys window`：`Window #6 u0 InputMethod` 正常存在；导航栏 `frame=[0,2208][1080,2340]`

### 项 3 · 敏感输入候选隐藏 + 普通字符直输
- 截图：`final-05-sensitive-direct.png`（另 `final-04-ninekey-bottom.png` 为敏感直输键盘侧证）
- 操作：切到 `app.agentime:id/editToken`（`inputType=textPassword`）后连点字母键，`adb shell input tap 71 1695` / `545 1831` / `310 1831`
- 实拍：面板上下文 chip 变为 **`敏感输入，已禁用 AI 上下文`**；草稿框内出现 **`qgd`** —— 字母**直接落字**，无拼音缓冲、无候选行；键盘第 3 排为 `Z X C V B N M 退格`（**无 `汉` 键**，即 `Keypad.buildDirect`），底排仅 `空格 / 回车`
- 敏感宿主里点 `加选区/加草稿` 只 toast `label_context_sensitive`（设计如此，未写入任何宿主内容）

### 项 4 · 切回普通框候选恢复
- 截图：`final-06-normal-candidates-restored.png`
- 操作：`adb shell input tap 540 1193` 聚焦普通框 `editBackend`（`inputType=textUri`），随后点按 `N / I / H / A / O` 键（`925 1982`、`929 1601`、`603 1798`、`71 1798`、`1011 1601`）
- 实拍：候选行恢复出现（`哦阿 / 哦 / 噢 / 喔 / 奠 / 嚷` 等），`汉` 键回归，底排 `拼音 / 空格 / 回车 / 取消拼音` 完整可见；键底 y=2175，导航栏 2208 → 33px 净空
- 结论：**敏感态无候选 → 切回普通框候选恢复**，路径符合预期

### 项 5 · 窄屏 / Agent 工具栏
- 截图：`final-08-narrow-agent-toolbar.png`（720x1280，density 320，`adb shell wm size 720x1280` + `wm density 320`）
- 实拍：Agent 顶部工具栏 `返回普通输入 / 会话 / 九键` 仍为单行、autoSize 自动缩小、无截断；面板全部控件（上下文 chip、四按钮、草稿框、`帮我改/提问`、`librime 1.17.0` 状态行）与九宫/qwerty 键盘全在屏内，底排完整可见
- 复验后已恢复：`adb shell wm size reset` + `wm density reset` → `Physical size: 1080x2340`、`Physical density: 440`

## 3. 仍存在的问题

1. **正常键盘首次弹出时底部行被导航栏完全遮挡（132px，稳定复现）**
   - 证据：`final-01-normal-kb.png`、`final-01-normal-kb-b.png`、`v2-crop-bottom-normal.png`
   - 像素实测：该状态只有 3 排键（Q 排 y1733–1854、A 排 1880–2003、Z 排 2031–2154），第 4 排（`拼音/空格/回车/取消拼音`）被推到 y≥2205，落在导航栏 `frame=[0,2208][1080,2340]` 之下 → 只剩键顶白边
   - 缺失高度 132px ≈ 底排行高；正常态 IME `frame=[0,1582][1080,2340]`（高 758），可用态为 `[0,1458][1080,2340]`（高 882）
   - 触发路径：普通框首次聚焦/弹键盘即出现；一旦发生模式切换（Agent→`返回普通输入`）或出现候选行触发重新布局，底部行恢复正常
   - 推测（未改码验证）：`AgentImeService.kt:91-96` 的 windowInsets 监听在首帧拿到 `navBars.bottom` 后设置容器底 padding，但 IME 窗口高度未同步加上该 padding，导致最后一行被挤出窗口；重排后高度被修正
2. **模拟器环境不稳定（非 APK 缺陷，但影响复验）**
   - 复验期间模拟器至少 3 次自行重启（`Phone is starting...`、`uptime` 归零、`adb devices` 变 offline），每次需 `KEYCODE_WAKEUP` + `am start -n app.agentime/.MainActivity` 重新进场
   - `logcat` 抓到 **两次非本会话发起的重装**：`22:11:58` 与 `22:13:01` 均有 `Force stopping app.agentime ... installPackageLI` + `Killing app.agentime`，导致面板/键盘状态被清空、偶发 `cmd: Can't find service: input`
   - 已就此向父会话汇报；如需干净复验，建议先停掉并发的安装/构建进程
3. **敏感宿主里 `九键` 开关无效（符合设计但交互无提示）**：敏感态点 `九键` 仍是直输键盘（无九宫格），未观察到提示 toast；若产品期望"敏感态禁用九键"，建议补一条可见提示
4. 复验中曾观察到 toast **`目标已变化，拒绝写入`**（`final-07-ninekey-bottom.png` 那一帧）：说明插入/替换时目标编辑器已变化时会拒绝写入，行为符合"不误写宿主"的设计，未计入缺陷

## 4. 截图清单（`docs\acceptance-evidence\`）

| 文件 | 内容 |
| --- | --- |
| `v2-agent-panel-a.png` | 项 1：Agent 面板顶部/状态标签/返回按钮（旧构建 `96682664…`） |
| `final-09-agent-panel-verified-build.png` | 项 1：同一面板，设备实装构建 `358C5DFD…` |
| `final-10-normal-kb-verified-build.png` | 普通键盘首帧（实装构建） |
| `final-11-back-to-normal-verified-build.png` | 返回普通输入生效（实装构建，底排净空 33px） |
| `v2-back-to-normal.png` | 返回普通输入后 |
| `final-07-ninekey-bottom.png` | 项 2：九键底排 26键/0/回车/退格（安全底距 31px） |
| `final-05-sensitive-direct.png` | 项 3：敏感输入候选隐藏 + `qgd` 直输 |
| `final-04-ninekey-bottom.png` | 项 3 侧证：敏感直输键盘（无汉键、底排空格/回车） |
| `final-06-normal-candidates-restored.png` | 项 4：切回普通框候选恢复 |
| `final-08-narrow-agent-toolbar.png` | 项 5：720x1280 窄屏 Agent 工具栏 |
| `final-01-normal-kb.png` / `final-01-normal-kb-b.png` / `v2-crop-bottom-normal.png` | 问题 1：底部行被导航栏遮挡（整改前/复现） |
