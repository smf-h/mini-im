param(
  [ValidateSet('connect','ping','single_e2e','ack_stress')]
  [string]$Mode = "ping",

  [string]$JavaExe = "java",
  [string]$JavacExe = "javac",

  [string]$WsUrl = "ws://127.0.0.1:9001/ws",
  [string]$WsUrls = "",
  [switch]$RolePinned,

  [string]$JwtSecret = "change-me-please-change-me-please-change-me",
  [string]$JwtIssuer = "mini-im",
  [int]$Clients = 200,
  [int]$DurationSeconds = 60,
  [int]$WarmupMs = 1000,
  [int]$PingIntervalMs = 1000,
  [int]$MsgIntervalMs = 100,
  [long]$UserBase = 100000,
  [switch]$Reconnect,
  [int]$ReconnectJitterMs = 200,
  [int]$FlapIntervalMs = 0,
  [int]$FlapPct = 0,
  [int]$SlowConsumerPct = 0,
  [int]$SlowConsumerDelayMs = 0,
  [int]$NoReadPct = 0,
  [int]$BodyBytes = 0,
  [int]$Inflight = 0,
  [switch]$OpenLoop,
  [int]$MaxInflightHard = 200,
  [long]$MaxValidE2eMs = 600000,

  [string]$AckStressTypes = "delivered,read",
  [int]$AckEveryN = 1
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$outDir = Join-Path $scriptDir ".out"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$src = Join-Path $scriptDir "WsLoadTest.java"
& $JavacExe -encoding UTF-8 -d $outDir $src

$args = @(
  "--mode", $Mode,
  "--clients", "$Clients",
  "--durationSeconds", "$DurationSeconds",
  "--warmupMs", "$WarmupMs",
  "--pingIntervalMs", "$PingIntervalMs",
  "--msgIntervalMs", "$MsgIntervalMs",
  "--userBase", "$UserBase",
  "--jwtSecret", $JwtSecret,
  "--jwtIssuer", $JwtIssuer,
  "--reconnect", $(if ($Reconnect) { "true" } else { "false" })
  "--reconnectJitterMs", "$ReconnectJitterMs"
  "--flapIntervalMs", "$FlapIntervalMs"
  "--flapPct", "$FlapPct"
  "--slowConsumerPct", "$SlowConsumerPct"
  "--slowConsumerDelayMs", "$SlowConsumerDelayMs"
  "--noReadPct", "$NoReadPct"
  "--bodyBytes", "$BodyBytes"
  "--inflight", "$Inflight"
)

if ($OpenLoop) {
  $args += @("--openLoop")
  $args += @("--maxInflightHard", "$MaxInflightHard")
}
$args += @("--maxValidE2eMs", "$MaxValidE2eMs")
$args += @("--ackStressTypes", "$AckStressTypes")
$args += @("--ackEveryN", "$AckEveryN")

if ($WsUrls -and $WsUrls.Trim().Length -gt 0) {
  $args += @("--wsList", $WsUrls)
  if ($RolePinned) { $args += @("--rolePinned") }
} else {
  $args += @("--ws", $WsUrl)
}

& $JavaExe -cp $outDir WsLoadTest @args
