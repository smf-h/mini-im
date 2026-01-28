param(
  [string]$WsUrlA = "ws://127.0.0.1:9001/ws",
  [string]$WsUrlB = "ws://127.0.0.1:9002/ws",
  [string]$HttpBaseA = "http://127.0.0.1:8080",
  [string]$HttpBaseB = "http://127.0.0.1:8082",
  [string]$UserAName = "cluster_a",
  [string]$UserBName = "cluster_b",
  [string]$Password = "p",
  [int]$TimeoutMs = 12000,
  [ValidateSet("auto","push","notify","none")]
  [string]$GroupStrategyMode = "auto",
  [switch]$NoMultiDevice
)

$ErrorActionPreference = 'Stop'

# Entry script for multi-instance WS smoke tests (PowerShell 5.1).
# Keep this file ASCII-safe (UTF-8 no BOM with non-ASCII literals can break parsing on some Windows setups).

function Assert-PortOpen([Uri]$u) {
  $hostName = $u.Host
  $port = $u.Port
  if ($port -lt 0) { throw "WsUrl must include a port: $u" }
  $ok = (Test-NetConnection -ComputerName $hostName -Port $port -WarningAction SilentlyContinue).TcpTestSucceeded
  if (-not $ok) { throw "Cannot connect to $u (is the server running?)" }
}

Assert-PortOpen ([Uri]$WsUrlA)
Assert-PortOpen ([Uri]$WsUrlB)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$javaFile = Join-Path $scriptDir "WsClusterSmokeTest.java"
$outDir = Join-Path $scriptDir ".out"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Push-Location $scriptDir
try {
  javac -encoding UTF-8 -d "$outDir" "$javaFile"

  $json = java -cp "$outDir" `
    -DwsA="$WsUrlA" `
    -DwsB="$WsUrlB" `
    -DhttpA="$HttpBaseA" `
    -DhttpB="$HttpBaseB" `
    -DuserAName="$UserAName" `
    -DuserBName="$UserBName" `
    -Dpassword="$Password" `
    -DtimeoutMs="$TimeoutMs" `
    -DgroupMode="$GroupStrategyMode" `
    -DmultiDevice="$(-not $NoMultiDevice)" `
    WsClusterSmokeTest

  $json
} finally {
  Pop-Location
}
