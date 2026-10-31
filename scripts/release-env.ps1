# Loads AgentIME release signing credentials from OUTSIDE the repository into
# the environment variables consumed by app/build.gradle.kts.
#
#   . .\scripts\release-env.ps1
#   gradle --no-daemon clean assembleRelease
#
# Default credential file (never committed):
#   %USERPROFILE%\.android-keys\agentime-release.properties
# Override with $env:AGENTIME_KEY_CREDENTIALS or -Path.
# See docs/release-signing.md.
[CmdletBinding()]
param(
    [string]$Path = $(if ($env:AGENTIME_KEY_CREDENTIALS) {
            $env:AGENTIME_KEY_CREDENTIALS
        } else {
            Join-Path $HOME ".android-keys\agentime-release.properties"
        })
)

if (-not (Test-Path -LiteralPath $Path)) {
    throw "Release credentials not found: $Path. See docs/release-signing.md."
}

$settings = @{}
foreach ($line in Get-Content -LiteralPath $Path) {
    $trimmed = $line.Trim()
    if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
    $idx = $trimmed.IndexOf('=')
    if ($idx -lt 1) { continue }
    $settings[$trimmed.Substring(0, $idx).Trim()] = $trimmed.Substring($idx + 1).Trim()
}

$expected = @{
    AGENTIME_STORE_FILE     = 'storeFile'
    AGENTIME_STORE_PASSWORD = 'storePassword'
    AGENTIME_KEY_ALIAS      = 'keyAlias'
    AGENTIME_KEY_PASSWORD   = 'keyPassword'
}

foreach ($name in $expected.Keys) {
    $value = $settings[$expected[$name]]
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Missing '$($expected[$name])' in $Path"
    }
    Set-Item -Path "env:$name" -Value $value
}

Write-Host "Release signing environment loaded from $Path"
Write-Host "  AGENTIME_STORE_FILE = $env:AGENTIME_STORE_FILE"
Write-Host "  AGENTIME_KEY_ALIAS  = $env:AGENTIME_KEY_ALIAS"
