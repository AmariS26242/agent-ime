# 文档索引

本目录按时间线记录 Agent 输入法的验收过程与关键决策。原始证据（截图、控件树、构建日志、APK）体积大，**不入库**，只保留在本机同一路径下：

```
docs/acceptance-evidence/        # 4 份报告 + measure_ime.py 入库；png/xml/log/apk/txt 由 .gitignore 排除
```

## 交付与配置

| 文档 | 内容 |
| --- | --- |
| [Release-内测候选版-验收-2026-10-31.md](<Release-内测候选版-验收-2026-10-31.md>) | 内测候选版总验收：签名链路、CI 流水线、Android 35 功能验收、覆盖升级、API 29 inset 补测、已知风险 |
| [release-signing.md](<release-signing.md>) | Release keystore 位置、环境变量、本地/CI 构建、GitHub Actions Secrets、证书轮换 |

## 输入引擎

| 文档 | 内容 |
| --- | --- |
| [Rime-librime-集成验收报告-2026-10-31.md](<Rime-librime-集成验收报告-2026-10-31.md>) | Rime 集成验收：环境矩阵、native 依赖与 ABI 闭包、APK 客观证据、脚本回放 |
| [中文输入引擎选型-2026-10-31.md](<中文输入引擎选型-2026-10-31.md>) | 引擎选型决策（librime + 朙月拼音）与九键方向、后续接入顺序 |

## UI 与键盘验收

| 文档 | 内容 |
| --- | --- |
| [模拟器验收-2026-10-30.md](<模拟器验收-2026-10-30.md>) | 10-30 定时模拟器验收（含最新复验补充） |
| [模拟器验收-2026-10-31-追加.md](<模拟器验收-2026-10-31-追加.md>) | 10-31 收口追加：后端测试、Messaging 场景、503 夹具遗留 |
| [acceptance-evidence/REPORT-final-single-2026-10-31.md](<acceptance-evidence/REPORT-final-single-2026-10-31.md>) | 最终返工验收：根因、单文件修改、构建与安装一致性、证据清单 |
| [acceptance-evidence/REPORT-final-ui-2026-10-31.md](<acceptance-evidence/REPORT-final-ui-2026-10-31.md>) | 最终返工复验：A/B 修复复核 + C 场景实测、遗留问题 |
| [acceptance-evidence/REPORT-final-device-recheck-2026-10-31.md](<acceptance-evidence/REPORT-final-device-recheck-2026-10-31.md>) | 最终 APK 设备复验：安装与 IME 恢复、五项验收证据 |
| [acceptance-evidence/after-fix-review-2026-10-31.md](<acceptance-evidence/after-fix-review-2026-10-31.md>) | UI 定向复核与修正：状态标签、闭包赋值、敏感态九键缓冲 |

## CI 日志

| 文件 | 内容 |
| --- | --- |
| [ci-logs/github-actions-41214237991.log](<ci-logs/github-actions-41214237991.log>) | 早期 Release/CI 运行日志（排障留档） |

## 约定

- 新验收记录命名：`<主题>-验收-YYYY-MM-DD.md`；多轮复验放 `docs/acceptance-evidence/`。
- 原始截图/控件树/APK/构建日志只在本地保留，提交前用 `git status` 确认没有把二进制证据带进仓库。
