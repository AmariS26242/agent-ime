# 启动 Agent IME 后端代理（本机开发 / 手机联调用）
# 用法:
#   powershell -ExecutionPolicy Bypass -File run-backend.ps1            # 只允许本机（adb reverse 场景）
#   powershell -ExecutionPolicy Bypass -File run-backend.ps1 -Lan       # 允许局域网（手机 Wi-Fi 直连，需放行防火墙）
#   powershell -ExecutionPolicy Bypass -File run-backend.ps1 -Mock      # 离线逐字流，不需要 Key
param(
    [switch]$Mock,
    [switch]$Lan,
    [int]$Port = 8787
)

# 上游 Key 默认取用户级环境变量 DEEPSEEK_API_KEY
if (-not $env:DEEPSEEK_API_KEY) {
    $env:DEEPSEEK_API_KEY = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'User')
}
# 客户端共享令牌（APK 默认写的是同一个值）
if (-not $env:CLIENT_TOKEN) { $env:CLIENT_TOKEN = 'dev-agentime-token' }
if (-not $env:UPSTREAM_MODEL) { $env:UPSTREAM_MODEL = 'deepseek-chat' }
$env:HOST = if ($Lan) { '0.0.0.0' } else { '127.0.0.1' }
$env:PORT = "$Port"
if ($Mock) { $env:MOCK_UPSTREAM = '1' } else { $env:MOCK_UPSTREAM = '' }

Write-Host "[run-backend] host=$env:HOST port=$env:PORT model=$env:UPSTREAM_MODEL mock=$env:MOCK_UPSTREAM"
Write-Host "[run-backend] key set: $([bool]$env:DEEPSEEK_API_KEY)"
if ($Lan) {
    # 提示：手机访问需要 Windows 防火墙放行（管理员 PowerShell 执行一次）
    Write-Host "[run-backend] 手机访问地址示例: http://<本机WLAN IP>:$Port/v1/chat/stream"
    Write-Host "[run-backend] 若手机连不上，用管理员 PowerShell 放行端口:"
    Write-Host "  New-NetFirewallRule -DisplayName 'AgentIME Backend $Port' -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Private"
}
Set-Location $PSScriptRoot
& python app.py
