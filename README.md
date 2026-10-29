# Agent 输入法（第一阶段技术演示）

本仓库是“个人 Agent 输入法”第一阶段的**实现代码**，并非可上线的产品。请阅读“已知限制”，其中若干验收项（尤其是真机验收与中文输入质量）**尚未通过**。

## 组成

- `app/` — Android Kotlin `InputMethodService`
- `backend/` — Python 标准库后端代理（仅使用标准库，持有上游 API Key）

## 关键能力

- 真实 `InputMethodService`（不伪装网页键盘）
- 工具条内单一 `Agent` 入口；面板有**提问态**（内部草稿 + 上下文条 + QWERTY 键区）和**回答态**（收起键区、追问/复制/插入，改写模式额外提供替换与撤销）
- 内部草稿与宿主草稿分离：按键写入内部 `EditText`，不写入宿主输入框
- 上下文默认不读取。用户必须点击“加选区/加草稿/粘贴”。面板常驻上下文摘要，可展开/收起全文
- 敏感输入判定（密码、`TYPE_TEXT_FLAG_NO_SUGGESTIONS`、IME 隐私标志）禁用 AI 上下文获取
- 请求可取消（协程 + OkHttp Call 关闭）、超时/失败显示重试，失败保留草稿
- 写入前核对：宿主连接代号、字段 `id`/`inputType`、宿主草稿全文、选区原文逐项校验；任一变化拒绝写入
- 改写撤销采用**事务快照**：记录写入前的正文、替换区间与文本，撤销时要求正文、区间文本与区间完全一致，否则拒绝
- `onStartInputView` 每次切换输入框都会递增连接代号，旧结果与迟到的回调被丢弃
- 会话闲置 15 分钟过期；`结束会话` 清空内存草稿与临时快照
- 不写正文日志；后端仅统计次数、用量、延迟、错误

## Android 构建

要求：JDK 17、Android SDK `compileSdk=35`、Gradle 8.7+ 可运行 `gradlew`（本仓库不含 gradle wrapper；请在你机器上以 `gradle` 生成或使用 Android Studio）。

```bash
gradle assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

安装后：
1. 打开 App 完成引导（启用输入法、选择输入法）
2. 填写后端地址与客户端令牌（**不是**上游 DeepSeek Key）
3. 任意输入框切换到 Agent 输入法，工具条点击 `Agent`

## 后端运行

```bash
cd backend
cp .env.example .env   # 编辑填写 UPSTREAM_API_KEY 与 CLIENT_TOKEN
python3 -m app
```

启用环境变量（示例）：

```bash
export UPSTREAM_API_KEY=sk-...
export CLIENT_TOKEN=$(python3 -c "import secrets;print(secrets.token_urlsafe(32))")
export DAILY_QUOTA=50
export TIMEOUT_SECONDS=35
export UPSTREAM_MODEL=deepseek-flash
python3 -m app
```

默认 `UPSTREAM_MODEL=deepseek-flash`（已验证可用）。如需其他型号，可通过 `UPSTREAM_MODEL` 覆盖。后端向上游发送 `thinking: {"type": "disabled"}`，关闭上游思考，以避免长思考拖长首字延迟。

### 后端测试

```bash
cd backend
python3 -m unittest discover -s tests -v
```

**未在当前环境运行过以上测试**（本次只交付代码）。测试覆盖：健康检查、鉴权、指标鉴权、配额计数、指标不含正文。上游用到真实 DeepSeek Key 的连通性**未测**。

## 数据链路与隐私声明（请如实对待）

1. Android 客户端 → 你的后端（含鉴权头与用户输入）
2. 后端 → DeepSeek（`UPSTREAM_API_KEY` 只在服务端；Android 侧无该 Key）
3. DeepSeek 返回 → 后端 → Android

- 本后端不打印请求/响应正文，不持久化正文；仅内存中按天计数配额和累计指标
- 但**上游 DeepSeek 服务自身的处理/保留政策由 DeepSeek 决定**，本应用不能代表“任何服务都不保存”。面向内测用户必须显著说明这一点
- 客户端令牌 `CLIENT_TOKEN` 是共享密钥，不是每用户凭证；内测阶段用最小代价交付，不能据此宣称多用户隔离

## 中文拼音输入

**未集成任何成熟中文输入引擎。** `app/src/main/java/app/agentime/PinyinDictionary.kt` 是一个**明确标注的离线有限词典 POC**：硬编码数十条拼音-候选，仅演示“键入拼音后选候选”。“全量中文输入”的验收 **PENDING**。

选型要求（提交给 Codex 审查）：许可证、词库许可证、离线能力、集成成本。可选方向（不构成推荐，未做评估）：Rime（`librime`，需自备词库并核对许可）、fcitx5 词库、Sogou/Baidu 词库（多数许可受限）。**不要将它们作为现有依赖声明**：本仓库未包含它们。

## 已知限制（如实）

- **中文输入未达可用水平**：仅为有限 POC 词典。要内测必须先解决。
- **未在真实 Android 设备验证**：宿主草稿保护、输入框切换、写入拒绝等均基于 API 语义实现，**尚未在真机演示**。不得据此宣称真机验收通过。
- 仅覆盖“问 Agent + 帮我改”。“帮我回”“帮我记”为第二阶段，未实现
- 取消：客户端在协程中对 `Call` 调用 `cancel()`，可停止仍在传输的 HTTP 连接；后端不主动停止已经回上游的请求，仍会占用上游配额
- 客户端令牌是共享的，不是账号系统；无每用户会话隔离
- 客户端 `usesCleartextTraffic=true` 便于局域网联调；生产必须使用 TLS 且应关闭
- `gradle wrapper` 未包含（待生成）；`gradle assembleDebug` 需你本机已有 Gradle
- 后端代理使用**普通 Python 标准库**，无流式响应、无检索、无结构化预算熔断；`urllib` 在部分平台超时表现可能与预期不同
- 指标 `latency_ms_total` 为累计值，需自行除以次数计算均值；这只是最小可观测性，不是生产指标系统
- 撤销依赖“事务快照”比对（字段 + 全文 + 区间 + 区间文本），在宿主实现非文本编辑（如富文本、撤销栈回放）时仍可能不完全一致；真机需进一步验证
- 未做无障碍/国际化（无英文 UI）；颜色和触摸尺寸按规范实现

## 验收对照（诚实）

| 关卡 | 状态 |
|---|---|
| A 可重复构建 | 部分：Gradle 文件齐全，但未在本环境执行 `gradle assembleDebug`，无 wrapper |
| B 真机普通/内部中文输入 | 未验证；普通中文仅 POC 词典 |
| C 问答/追问/三种改写/复制/受控插入替换 | 代码实现，未在设备验证；改写仅一个主结果 |
| D 输入框切换、原文变化、取消、断网、超时、敏感输入、会话过期 | 代码实现边界校验，未真机验证 |
| E 截图/测试记录 | 暂未提供（无设备） |
| F 内测与耗时指标 | 未开始 |

## 截图

待真机运行后补。
