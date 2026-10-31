# Release 签名配置

> 本文档记录 Agent 输入法 Release 包的签名方案。**密码和 keystore 本身不在仓库里**，提交前请确认 `git status` 中没有 `.jks` / `keystore.properties`。

## 1. 密钥材料在哪

| 项目 | 值 |
| --- | --- |
| Keystore | `%USERPROFILE%\.android-keys\agentime-release.jks`（PKCS12，RSA 4096，有效期 10000 天） |
| 凭据文件 | `%USERPROFILE%\.android-keys\agentime-release.properties`（含 storePassword / keyPassword） |
| Alias | `agentime-release` |
| 证书 SHA-1 | `53:46:2E:87:14:7E:EF:6D:C0:C6:F3:9B:C3:BF:39:31:71:7A:B4:21` |
| 证书 SHA-256 | `2E:9B:48:B2:4F:0C:42:30:95:94:B9:05:22:BD:BA:D6:83:7C:F1:84:18:66:22:28:70:D1:C6:03:29:4A:CA:1B` |
| E: 盘备份 | `E:\AgentImeTest\keys\`（jks + properties 各一份） |
| base64（CI 用） | `%USERPROFILE%\.android-keys\agentime-release.jks.base64` |

`subject` 为 `CN=AgentIME Release, OU=Mobile, O=AgentIME, L=Shanghai, ST=Shanghai, C=CN`。

**必须备份**：keystore 或密码丢失后，已安装的内测包无法再升级（只能卸载重装；将来上架后不可恢复）。建议把这两个文件放进密码管理器/离线备份。

## 2. Gradle 如何取到签名

`app/build.gradle.kts` 只从环境变量读取签名信息，缺任何一项时 release 变体保持未签名（产出 `app-release-unsigned.apk`），构建不会中断：

| 环境变量 | 含义 |
| --- | --- |
| `AGENTIME_STORE_FILE` | keystore 绝对路径 |
| `AGENTIME_STORE_PASSWORD` | keystore 密码 |
| `AGENTIME_KEY_ALIAS` | key alias |
| `AGENTIME_KEY_PASSWORD` | key 密码 |
| `AGENTIME_REQUIRE_SIGNING=true` | 仅 CI：缺签名凭据时直接构建失败，避免把未签名包当签名包发出去 |

## 3. 本地构建

```powershell
. E:\AgentImeTest\env.ps1
. .\scripts\release-env.ps1          # 从仓库外读取凭据并注入环境变量
gradle --no-daemon clean assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。

升级测试需要更高 versionCode 时（不改代码）：

```powershell
gradle --no-daemon assembleRelease -PappVersionCode=3 -PappVersionName=0.2.0-internal2
```

默认版本见 `app/build.gradle.kts`：`versionCode = 2`、`versionName = 0.2.0-internal1`。

## 4. CI 构建

工作流：`.github/workflows/release.yml`

- 触发：`workflow_dispatch`（可填 versionCode / versionName 覆盖）或推送 `v*` tag。
- 步骤：解码 keystore → `assembleRelease` → `apksigner verify` 并断言证书 SHA-256 等于本仓库 release key → 断言双 ABI + `librime.so` / `librime_jni.so` + `assets/rime` → 输出 APK SHA-256 与签名指纹到 Job Summary → 上传 artifact。

### 配置 Secrets（一次性）

```powershell
cd C:\Users\A'maris\Documents\ChatGPT\agentime
$cred = "$HOME\.android-keys\agentime-release.properties"
$kv = @{}
Get-Content $cred | Where-Object { $_ -match '=' -and $_ -notmatch '^\s*#' } | ForEach-Object {
  $k, $v = $_ -split '=', 2
  $kv[$k.Trim()] = $v.Trim()
}
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($kv['storeFile']))
$b64 | gh secret set RELEASE_KEYSTORE_BASE64 --repo AmariS26242/agent-ime
$kv['storePassword'] | gh secret set RELEASE_STORE_PASSWORD --repo AmariS26242/agent-ime
$kv['keyAlias']      | gh secret set RELEASE_KEY_ALIAS      --repo AmariS26242/agent-ime
$kv['keyPassword']   | gh secret set RELEASE_KEY_PASSWORD   --repo AmariS26242/agent-ime
```

`gh` 位于 `E:\Program Files\GitHub CLI\gh.exe`，当前账号 `AmariS26242` 已有 `repo` + `workflow` scope。

### 手动触发

```powershell
gh workflow run release.yml --repo AmariS26242/agent-ime
gh run watch --repo AmariS26242/agent-ime
```

## 5. 校验签名

```powershell
. E:\AgentImeTest\env.ps1
& "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --verbose --print-certs app\build\outputs\apk\release\app-release.apk
```

期望：`Verified using v1/v2/v3 scheme (APK Signature Scheme v2/v3) = true`，Signer #1 证书 SHA-256 等于上表指纹。

## 6. 轮换 / 吊销

1. 新 keystore 换一个文件名与 alias，更新 `.properties` 与 GitHub Secrets（key 名不变）。
2. 旧包签名不同，内测用户必须卸载重装；在发布说明里写清楚。
3. 轮换后同步更新本文档的指纹表和 `release.yml` 中的 `EXPECTED_FP`。
