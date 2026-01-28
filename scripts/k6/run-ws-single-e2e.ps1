param(
  [string]$WsUrls = "ws://127.0.0.1:9001/ws;ws://127.0.0.1:9002/ws",
  [string]$JwtSecret = "change-me-please-change-me-please-change-me",
  [string]$JwtIssuer = "mini-im",
  [int]$UserBase = 100000,
  [int]$Vus = 200,
  [string]$Duration = "5m",
  [int]$MsgIntervalMs = 50,
  [int]$WarmupMs = 1000,
  [switch]$RolePinned
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-K6Exe() {
  try {
    $cmd = Get-Command k6 -ErrorAction Stop
    if ($cmd -and $cmd.Path) { return $cmd.Path }
  } catch {
    # ignore
  }

  $candidates = @(
    "C:\\Program Files\\k6\\k6.exe",
    "C:\\Program Files (x86)\\k6\\k6.exe"
  )
  foreach ($c in $candidates) {
    if (Test-Path -LiteralPath $c) { return $c }
  }

  return "k6"
}

$k6Exe = Resolve-K6Exe

$env:WS_URLS = $WsUrls
$env:JWT_SECRET = $JwtSecret
$env:JWT_ISSUER = $JwtIssuer
$env:USER_BASE = "$UserBase"
$env:VUS = "$Vus"
$env:DURATION = $Duration
$env:MSG_INTERVAL_MS = "$MsgIntervalMs"
$env:WARMUP_MS = "$WarmupMs"
$env:ROLE_PINNED = $(if ($RolePinned) { "1" } else { "0" })

& $k6Exe run ".\\scripts\\k6\\ws_single_e2e.js" --vus $Vus --duration $Duration
