# 最终返工复验报告（A/B 修复复核 + C 场景实测）

- 时间：2026-10-31 22:24–22:44（Asia/Shanghai）
- 设备：emulator-5554（Android 15 / SDK 35，1080x2340，440dpi；窄屏用例临时 720x1280/320dpi 后已 reset）
- 源码基线：`app/src/main/java/app/agentime/AgentImeService.kt`，1316 行 / 55,637 bytes（mtime 22:40:49，与 22:08:38 版本一致；本轮未保留任何未验证改动）

## 1. 构建（ASCII 联接路径）

- 联接：`C:\Users\A'maris\Documents\ChatGPT\agentime` → `C:\Users\A'maris\Documents\ChatGPT\agent输入法\project`
- 环境：`. E:\AgentImeTest\env.ps1`（JAVA_HOME=jdk-17.0.21.0.2+1、ANDROID_SDK_ROOT=E:\AgentImeTest\sdk、GRADLE_USER_HOME=E:\AgentImeTest\gradle-home）
- 命令：`gradle --no-daemon clean assembleDebug`，工作目录经 `gradle -p C:\Users\A'maris\Documents\ChatGPT\agentime` 指定（本会话 shell 的 `Set-Location` 到该 Junction 被宿主拒绝，`-p` 等价且仍走 ASCII 路径，CMake 不再崩溃）
- 日志：`docs/acceptance-evidence/build-final-ui-2026-10-31.log`（4429 bytes，22:41:39）
  - 第 89 行：`BUILD SUCCESSFUL in 45s`
  - `48 actionable tasks: 47 executed, 1 up-to-date`，退出码 0
- 产物：`app/build/outputs/apk/debug/app-debug.apk`，12,825,374 bytes，SHA-256 `FA0B83C60065B7509B600AA5DC145EECB523A71C5580BE8337E70F3819B02C61`
- 安装：`adb -s emulator-5554 install -r` → `Success`；`dumpsys package` lastUpdateTime=2026-10-31 22:41:48

## 2. 代码复核（行号对应当前源码 1316 行）

### A. 属性赋值 + 编辑器切换重建
| 位置 | 内容 |
| --- | --- |
| `AgentImeService.kt:48-49` | `private var rebuildAgentKeypad: (() -> Unit)?`、`clearAgentComposition` |
| `:101-119` | `onStartInput`：同一 editor 仅刷新身份（:110-113），不同 editor 走 `establishNewSession(info)`（:118） |
| `:121-145` | `onStartInputView`：`:136 clearCompositionUi()`、`:137 renderNormalKeypad(rootNormal)`、`:138 rebuildAgentKeypad?.invoke()`；`:141-143` 同 editor 重启时保留 agentVisible，否则关闭；`:144 applyMode()` |
| `:231-239` | `clearCompositionUi()`：清 pinyin/nineKey 缓冲、候选容器 GONE，`:236` 调 `clearAgentComposition` |
| `:762-774` | 在局部 `rebuildAskKeypad/askCandidateRow` 定义之后赋值：`:765 rebuildAgentKeypad = { rebuildAskKeypad() }`；`:766-773 clearAgentComposition = { askPinyin=""; askNineBuffer=""; askNineKey=false; askCandidateRow.removeAllViews(); visibility=GONE; btnNineKeyAgent 文案复位 }`；`:774` 首次 rebuild |
| `:250-251` | 进入 Agent 时 `applyMode()` 前再 `rebuildAgentKeypad?.invoke()`，保证敏感直输模式即时生效 |

实测：敏感输入法（token）→ 返回普通输入 → 再点后端地址，Agent 面板/键盘均按当前 editor 重建，无残留拼音缓冲（b-04→b-07→b-08）。

### B. 敏感态只藏候选行，caption 常显，草稿直输、九键不积缓冲
| 位置 | 内容 |
| --- | --- |
| `:631-656` | `refreshAskCandidates()`：`:632-636` hostSensitive 时仅 `removeAllViews()` + `askCandidateRow.visibility = GONE` 后 return（不动 caption） |
| `:668` | `askCaption.visibility = View.VISIBLE`（无条件，敏感态也显示 `librime 1.17.0 · 朙月拼音`） |
| `:726-734` | hostSensitive 走 `keypad?.buildDirect(rows)`，逐字符 `edit.text.append(c)` 并清空缓冲（内部草稿直输） |
| `:743-753` | 敏感态 `btnNineKeyAgent` 直接 `return@setOnClickListener`（`askNineKey=false`、清缓冲、文案复位、重建），不积累数字缓冲 |
| `:1279-1291` | 敏感态禁用 btnAddSelection/AddDraft/Paste/Insert/Replace（isEnabled=false） |
| `:1297-1298` | 上下文 chip 显示「敏感输入，已禁用 AI 上下文」 |

实测（b-07）：chip 为「敏感输入，已禁用 AI 上下文」，`librime 1.17.0 · 朙月拼音` 常显，无候选行，键盘为 direct（无 `汉` 键、无 `拼音/取消拼音`，底行仅 `空格/回车`）。

### C. 常规场景复查
| 场景 | 结果 | 截图 |
| --- | --- | --- |
| 普通输入框敏感字段直输 | token（textPassword）聚焦 → 直输键盘，点 A/B/C 直接落密码点，无候选行 | `b-05-sensitive-direct.png`、`b-06-sensitive-typed.png` |
| 切回普通框候选恢复 | 后端地址（textUri）聚焦后点 N/I → 候选「你 呢 拟 尼 泥 妳」，宿主 URL 未变 | `b-09-candidates-restored.png` |
| Agent 返回按钮 | 面板内点「返回普通输入」→ 回到普通键盘，底行完整 | `b-04-back-normal.png` |
| Agent 面板状态标签 | 面板顶部单行「返回普通输入 / 会话 / 九键」，其下「输入到 Agent」 | `b-02-agent-panel.png` |
| 九键底行安全间距 | Agent 九键：`26键/0/回车/退格` 键底 y≈2178，导航栏顶 y=2208 → 30px（≈11dp）净空 | `b-03-ninekey-agent.png` |
| 窄屏 / 工具栏 | 720x1280/320dpi：面板工具栏单行不截断，全部控件与底行在屏内；九键同 | `b-11-narrow-panel.png`、`b-12-narrow-ninekey.png` |

## 3. 遗留问题（需决策，未在本轮修）

1. **普通键盘首次弹出时第 4 排（拼音/空格/回车/取消拼音）被导航栏遮住（稳定复现）**
   - 证据：`b-01-normal-first-pop.png`；同帧 `dumpsys window`：IME `frame=[0,1582][1080,2340]`（高 758）
   - 对比可用态：任何一次 `applyMode()` 重排后 `frame=[0,1450][1080,2340]`（高 890，`b-04`/`b-08`/`b-09`）
   - 像素实测：首弹帧在 y=2184 才出现底行 4 个键帽顶边（中心 102/388/689/944），导航栏 [0,2208][1080,2340] 覆盖其余部分；缺失高度 132px = 一行键高
   - 复现路径：force-stop → 启动 → 点输入框（或隐藏键盘后再次显示）；切一次 Agent→返回普通输入即恢复
   - 已试无效方案：在 `onCreateInputView` 里提前 `container.setPadding(0,0,0,navigation_bar_height)`（构建 81656C…，现象不变，窗口高度仍 758）→ 说明窗口高度未跟随 view 高度，问题在 IME 窗口尺寸与 inset 时序，而非 padding 施加时机
   - 建议方向：`onCreateInputView` 返回后由 `onStartInputView`/insets 回调里 `window?.window?.setLayout(MATCH_PARENT, measuredHeight)` 显式重设 IME 窗口高度；或改用 `setDecorFitsSystemWindows(false)` + 自绘底部安全区（需再验证）

2. **并发 worker 干扰（环境层面，非 APK 缺陷）**
   - `docs/acceptance-evidence/REPORT-final-device-recheck-2026-10-31.md`（22:21:39）为另一 worker 产出；它 22:06–22:22 在同一 emulator 做同一套验收，安装的是 SHA `96682664BB3473937B5A03C31E6FECCEA970B6AACE30944772F4097B8924902A` 的 APK，并留下 `v2-agent-panel-a.png`、`final-05-sensitive-direct.png`、`final-06-normal-candidates-restored.png`、`final-07-ninekey-bottom.png`、`final-08-narrow-agent-toolbar.png` 等截图
   - 期间设备被改过 `wm size 720x1280` / `wm density 320`，并出现两次非本会话发起的重装（22:11:58、22:13:01），一度导致点按穿透到宿主 App（弹出 Choose input method / 打开系统键盘设置）
   - 本报告所有截图均为 22:42–22:43 在本轮最终 APK（FA0B83C6…）安装后采集，`a-*.png` 为同源码早期构建（6C4DB2…）的对照帧

3. **敏感态上下文按钮无 disabled 视觉**：`isEnabled=false`（`:1279-1291`）但外观与可用态一致，点击仅 toast「敏感输入，已禁用 AI 上下文」；建议补灰态。

4. **敏感态点「九键」无任何提示**（` :743-753` 静默 return），符合设计但交互无反馈。

## 4. 截图清单（`docs\acceptance-evidence\`，均为本轮最终 APK）

| 文件 | 内容 |
| --- | --- |
| `b-01-normal-first-pop.png` | 遗留问题 1：首弹底行被导航栏遮挡（frame 758） |
| `b-02-agent-panel.png` | Agent 面板 + 状态标签「输入到 Agent」+ caption |
| `b-03-ninekey-agent.png` | Agent 九键，底排 26键/0/回车/退格，净空 30px |
| `b-04-back-normal.png` | 返回普通输入后（frame 890，底行完整） |
| `b-05-sensitive-direct.png` | 敏感输入框：direct 键盘、无候选行 |
| `b-06-sensitive-typed.png` | 敏感输入框直输密码点 |
| `b-07-sensitive-panel.png` | 敏感 + Agent 面板：caption 常显、候选行隐藏、direct 键盘 |
| `b-08-normal-switched.png` | 切回普通框后的布局 |
| `b-09-candidates-restored.png` | 普通框候选恢复「你 呢 拟 尼 泥 妳」 |
| `b-10-narrow-normal.png` | 720x1280 普通键盘 |
| `b-11-narrow-panel.png` | 720x1280 Agent 面板/工具栏不截断 |
| `b-12-narrow-ninekey.png` | 720x1280 九键 |

## 5. 合规项

- 未修改 Rime native / JNI、后端协议、隐私边界、宿主文本读写逻辑；仅复核既有 A/B 实现
- 本轮临时实验（inset 提前 padding）已回滚，源码 lines=1316 / bytes=55,637，与 22:08:38 基线一致
- 未执行 git reset，未删除任何资源
