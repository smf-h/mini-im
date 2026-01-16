param(
  [string]$JarPath = "target/mini-im-0.0.1-SNAPSHOT.jar",
  [string]$JavaExe = "java",
  [ValidateSet("auto","push","notify","none")]
  [string]$GroupStrategyMode = "auto",
  [string]$Password = "p",
  [switch]$NoMultiDevice,
  [switch]$SkipInfraCheck,
  [switch]$SkipBuild,
  [switch]$SkipMomentsSmoke,
  [int]$TimeoutMs = 20000,
  [switch]$KeepProcesses
)

$ErrorActionPreference = 'Stop'

# One-command runner for 2-instance WS smoke tests (PowerShell 5.1).
# Keep this file ASCII-safe.

function Resolve-JavaExe([string]$JavaExe) {
  if (-not $JavaExe -or $JavaExe.Trim().Length -eq 0) { $JavaExe = "java" }

  if (Test-Path -LiteralPath $JavaExe) { return $JavaExe }

  $resolved = $JavaExe
  try {
    $cmd = Get-Command $JavaExe -ErrorAction Stop
    if ($cmd -and $cmd.Path) { $resolved = $cmd.Path }
  } catch {
    # ignore
  }

  $needsFix = ($JavaExe.Trim().ToLower() -eq "java") -or ($resolved -match "\\\\Oracle\\\\Java\\\\javapath\\\\java\\.exe$") -or ($resolved -match "\\\\javapath\\\\java\\.exe$")
  if (-not $needsFix) { return $resolved }

  $javaHome = [Environment]::GetEnvironmentVariable("JAVA_HOME", "Process")
  if (-not $javaHome -or $javaHome.Trim().Length -eq 0) {
    $javaHome = [Environment]::GetEnvironmentVariable("JAVA_HOME", "User")
  }
  if ($javaHome -and $javaHome.Trim().Length -gt 0) {
    $candidate = Join-Path $javaHome "bin\\java.exe"
    if (Test-Path -LiteralPath $candidate) { return $candidate }
  }

  $candidates = @(& where.exe java 2>$null)
  foreach ($c in $candidates) {
    if (-not $c) { continue }
    $t = $c.Trim()
    if ($t.Length -eq 0) { continue }
    if ($t -match "\\\\javapath\\\\") { continue }
    if (Test-Path -LiteralPath $t) { return $t }
  }

  if ($candidates.Count -gt 0) {
    $t = $candidates[$candidates.Count - 1].Trim()
    if ($t.Length -gt 0 -and (Test-Path -LiteralPath $t)) { return $t }
  }

  return $resolved
}

$JavaExe = Resolve-JavaExe $JavaExe

function Ensure-EnvVarFromUser([string]$Name) {
  $item = Get-Item -Path ("Env:" + $Name) -ErrorAction SilentlyContinue
  $cur = if ($null -eq $item) { "" } else { $item.Value }
  if (-not [string]::IsNullOrWhiteSpace($cur)) { return }
  $userVal = [Environment]::GetEnvironmentVariable($Name, "User")
  if (-not [string]::IsNullOrWhiteSpace($userVal)) {
    Set-Item -Path ("Env:" + $Name) -Value $userVal
  }
}

# In Codex CLI each command may run in a fresh PowerShell process; import user-level secrets if present.
Ensure-EnvVarFromUser "IM_MYSQL_USERNAME"
Ensure-EnvVarFromUser "IM_MYSQL_PASSWORD"

function Assert-TcpOpen([string]$HostName, [int]$Port, [string]$Hint) {
  $ok = (Test-NetConnection -ComputerName $HostName -Port $Port -WarningAction SilentlyContinue).TcpTestSucceeded
  if (-not $ok) { throw "Cannot connect to ${HostName}:${Port}. $Hint" }
}

function Get-FreeTcpPort() {
  $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
  $listener.Start()
  try {
    return $listener.LocalEndpoint.Port
  } finally {
    $listener.Stop()
  }
}

function Wait-Port([string]$HostName, [int]$Port, [int]$TimeoutMs) {
  $deadline = [DateTime]::UtcNow.AddMilliseconds($TimeoutMs)
  while ([DateTime]::UtcNow -lt $deadline) {
    $ok = (Test-NetConnection -ComputerName $HostName -Port $Port -WarningAction SilentlyContinue).TcpTestSucceeded
    if ($ok) { return }
    Start-Sleep -Milliseconds 200
  }
  throw "Timeout waiting for ${HostName}:${Port}"
}

function Build-Jar([string]$JarPath) {
  mvn -q -DskipTests package
  if ($LASTEXITCODE -ne 0) {
    throw "mvn package failed (exit=$LASTEXITCODE). If you have a running 'java -jar $JarPath' process, stop it (jar may be locked), or rerun with -SkipBuild."
  }
  if (-not (Test-Path -LiteralPath $JarPath)) { throw "Jar not found after build: $JarPath" }
}

function Start-Instance([string]$Name, [int]$HttpPort, [int]$WsPort, [string]$InstanceId, [string]$GroupMode, [string]$JarPath) {
  $ts = Get-Date -Format "yyyyMMdd_HHmmss"
  $logDir = "logs/ws-cluster-smoke"
  New-Item -ItemType Directory -Force -Path $logDir | Out-Null
  $outLog = Join-Path $logDir "${Name}_${ts}.out.log"
  $errLog = Join-Path $logDir "${Name}_${ts}.err.log"

  $args = @(
    "-Dfile.encoding=UTF-8",
    "-jar", $JarPath,
    "--server.port=$HttpPort",
    "--im.gateway.ws.host=127.0.0.1",
    "--im.gateway.ws.port=$WsPort",
    "--im.gateway.ws.instance-id=$InstanceId",
    "--im.group-chat.strategy.mode=$GroupMode"
  )

  $p = Start-Process -FilePath $JavaExe -ArgumentList $args -PassThru -NoNewWindow -WorkingDirectory (Get-Location).Path -RedirectStandardOutput $outLog -RedirectStandardError $errLog
  return [pscustomobject]@{
    Name = $Name
    Process = $p
    HttpPort = $HttpPort
    WsPort = $WsPort
    OutLog = $outLog
    ErrLog = $errLog
  }
}

function Kill-JavaByServerPort([int]$Port) {
  try {
    $pattern = "--server\.port=$Port"
    $list = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match $pattern }
    foreach ($p in $list) {
      try { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue } catch { }
    }
  } catch {
    # ignore
  }
}

if (-not $SkipInfraCheck) {
  Assert-TcpOpen "127.0.0.1" 3306 "MySQL is required (see src/main/resources/application.yml)."
  Assert-TcpOpen "127.0.0.1" 6379 "Redis is required (see src/main/resources/application.yml)."
}

if (-not $SkipBuild) {
  Build-Jar $JarPath
}

$httpA = Get-FreeTcpPort
$httpB = Get-FreeTcpPort
$wsA = Get-FreeTcpPort
$wsB = Get-FreeTcpPort

$instA = $null
$instB = $null

try {
  $instA = Start-Instance -Name "gw-a" -HttpPort $httpA -WsPort $wsA -InstanceId "gw-a" -GroupMode $GroupStrategyMode -JarPath $JarPath
  $instB = Start-Instance -Name "gw-b" -HttpPort $httpB -WsPort $wsB -InstanceId "gw-b" -GroupMode $GroupStrategyMode -JarPath $JarPath

  Wait-Port "127.0.0.1" $instA.HttpPort $TimeoutMs
  Wait-Port "127.0.0.1" $instB.HttpPort $TimeoutMs
  Wait-Port "127.0.0.1" $instA.WsPort $TimeoutMs
  Wait-Port "127.0.0.1" $instB.WsPort $TimeoutMs

  $wsUrlA = "ws://127.0.0.1:$($instA.WsPort)/ws"
  $wsUrlB = "ws://127.0.0.1:$($instB.WsPort)/ws"
  $httpBaseA = "http://127.0.0.1:$($instA.HttpPort)"
  $httpBaseB = "http://127.0.0.1:$($instB.HttpPort)"

  $runArgs = @(
    "-WsUrlA", $wsUrlA,
    "-WsUrlB", $wsUrlB,
    "-HttpBaseA", $httpBaseA,
    "-HttpBaseB", $httpBaseB,
    "-TimeoutMs", ([Math]::Max(8000, $TimeoutMs)),
    "-GroupStrategyMode", $GroupStrategyMode
  )
  if ($NoMultiDevice) { $runArgs += "-NoMultiDevice" }

  powershell -ExecutionPolicy Bypass -File "scripts/ws-cluster-smoke-test/run.ps1" @runArgs
  if ($LASTEXITCODE -ne 0) { throw "ws-cluster smoke test failed (exit=$LASTEXITCODE)" }

  if (-not $SkipMomentsSmoke) {
    $momArgs = @(
      "-HttpBaseA", $httpBaseA,
      "-HttpBaseB", $httpBaseB,
      "-Password", $Password,
      "-TimeoutMs", ([Math]::Max(8000, $TimeoutMs))
    )

    powershell -ExecutionPolicy Bypass -File "scripts/moments-smoke-test/run.ps1" @momArgs
    if ($LASTEXITCODE -ne 0) { throw "moments smoke test failed (exit=$LASTEXITCODE)" }
  }
} finally {
  if (-not $KeepProcesses) {
    if ($instA -and $instA.Process) {
      Stop-Process -Id $instA.Process.Id -Force -ErrorAction SilentlyContinue
      try { Wait-Process -Id $instA.Process.Id -Timeout 5 -ErrorAction SilentlyContinue } catch { }
      Kill-JavaByServerPort $instA.HttpPort
    }
    if ($instB -and $instB.Process) {
      Stop-Process -Id $instB.Process.Id -Force -ErrorAction SilentlyContinue
      try { Wait-Process -Id $instB.Process.Id -Timeout 5 -ErrorAction SilentlyContinue } catch { }
      Kill-JavaByServerPort $instB.HttpPort
    }
  }
}
