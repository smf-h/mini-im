param(
  [string]$JavaExe = "java",
  [string]$JavacExe = "javac",

  [string]$WsUrls = "ws://127.0.0.1:9001/ws",
  [string]$HttpBase = "http://127.0.0.1:8080",

  [int]$Clients = 200,
  [int]$Senders = 20,
  [int]$DurationSeconds = 60,
  [int]$WarmupMs = 1500,
  [int]$MsgIntervalMs = 50,

  [string]$UserPrefix = "glt",
  [string]$Password = "p",
  [int]$ReceiverSamplePct = 30,
  [int]$BodyBytes = 0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$outDir = Join-Path $scriptDir ".out"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$src = Join-Path $scriptDir "WsGroupLoadTest.java"
& $JavacExe -encoding UTF-8 -d $outDir $src

$args = @(
  "--wsList", $WsUrls,
  "--http", $HttpBase,
  "--clients", "$Clients",
  "--senders", "$Senders",
  "--durationSeconds", "$DurationSeconds",
  "--warmupMs", "$WarmupMs",
  "--msgIntervalMs", "$MsgIntervalMs",
  "--userPrefix", $UserPrefix,
  "--password", $Password,
  "--receiverSamplePct", "$ReceiverSamplePct",
  "--bodyBytes", "$BodyBytes"
)

& $JavaExe -cp $outDir WsGroupLoadTest @args

