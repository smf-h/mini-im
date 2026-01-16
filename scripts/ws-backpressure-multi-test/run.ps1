param(
  [string]$JarPath = "target/mini-im-0.0.1-SNAPSHOT.jar",
  [string]$JavaExe = "java",
  [string]$JavacExe = "",

  [switch]$SkipInfraCheck,
  [switch]$SkipBuild,
  [int]$TimeoutMs = 20000,
  [switch]$KeepProcesses,

  [switch]$RunMomentsSmoke,
  [string]$Password = "p",

  [int]$Clients = 200,
  [int]$DurationSeconds = 60,
  [int]$WarmupMs = 1000,
  [int]$MsgIntervalMs = 10,
  [long]$UserBase = 100000,
  [int]$BodyBytes = 4000,

  [int]$SlowConsumerPct = 30,
  [int]$SlowConsumerDelayMs = 5000,
  [int]$NoReadPct = 30,

  [int]$BpLowBytes = 32768,
  [int]$BpHighBytes = 65536,
  [int]$BpCloseAfterMs = 1500
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

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

function Test-TcpOpen([string]$HostName, [int]$Port, [int]$ConnectTimeoutMs) {
  try {
    $client = [System.Net.Sockets.TcpClient]::new()
    $iar = $client.BeginConnect($HostName, $Port, $null, $null)
    $ok = $iar.AsyncWaitHandle.WaitOne($ConnectTimeoutMs, $false)
    if (-not $ok) {
      $client.Close()
      return $false
    }
    $client.EndConnect($iar)
    $client.Close()
    return $true
  } catch {
    return $false
  }
}

function Assert-TcpOpen([string]$HostName, [int]$Port, [string]$Hint) {
  if (-not (Test-TcpOpen -HostName $HostName -Port $Port -ConnectTimeoutMs 500)) {
    throw "Cannot connect to ${HostName}:${Port}. $Hint"
  }
}

function Import-DotEnvIfPresent() {
  $candidates = @(".env.local", ".env")
  foreach ($p in $candidates) {
    if (-not (Test-Path -LiteralPath $p)) { continue }
    $lines = Get-Content -LiteralPath $p -ErrorAction SilentlyContinue
    foreach ($line in $lines) {
      if (-not $line) { continue }
      $t = $line.Trim()
      if ($t.Length -eq 0) { continue }
      if ($t.StartsWith("#")) { continue }
      $idx = $t.IndexOf("=")
      if ($idx -le 0) { continue }
      $k = $t.Substring(0, $idx).Trim()
      $v = $t.Substring($idx + 1).Trim()
      if ($k.Length -eq 0) { continue }
      $existing = [Environment]::GetEnvironmentVariable($k, "Process")
      if ($null -eq $existing -or $existing.Length -eq 0) {
        [Environment]::SetEnvironmentVariable($k, $v, "Process")
      }
    }
  }
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
    if (Test-TcpOpen -HostName $HostName -Port $Port -ConnectTimeoutMs 300) { return }
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

function Start-Instance([string]$Name, [int]$HttpPort, [int]$WsPort, [string]$InstanceId, [string]$JarPath) {
  $ts = Get-Date -Format "yyyyMMdd_HHmmss"
  $logDir = "logs/bp-multi"
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
    "--im.gateway.ws.backpressure.write-buffer-low-water-mark-bytes=$BpLowBytes",
    "--im.gateway.ws.backpressure.write-buffer-high-water-mark-bytes=$BpHighBytes",
    "--im.gateway.ws.backpressure.close-unwritable-after-ms=$BpCloseAfterMs",
    "--im.gateway.ws.backpressure.drop-when-unwritable=true"
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
    $pattern = "--server\\.port=$Port"
    $list = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match $pattern }
    foreach ($p in $list) {
      try { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue } catch { }
    }
  } catch {
    # ignore
  }
}

function Resolve-JavacExe([string]$JavaExe, [string]$JavacExe) {
  if ($JavacExe -and $JavacExe.Trim().Length -gt 0) { return $JavacExe }
  if ($JavaExe -and $JavaExe.ToLower().EndsWith("java.exe")) {
    $candidate = Join-Path (Split-Path -Parent $JavaExe) "javac.exe"
    if (Test-Path -LiteralPath $candidate) { return $candidate }
  }
  return "javac"
}

if (-not $SkipInfraCheck) {
  Import-DotEnvIfPresent
  Assert-TcpOpen "127.0.0.1" 3306 "MySQL is required (see src/main/resources/application.yml)."
  Assert-TcpOpen "127.0.0.1" 6379 "Redis is required (see src/main/resources/application.yml)."
}

if (-not $SkipBuild) {
  Build-Jar $JarPath
}

$javacResolved = Resolve-JavacExe -JavaExe $JavaExe -JavacExe $JavacExe

$httpA = Get-FreeTcpPort
$httpB = Get-FreeTcpPort
$wsA = Get-FreeTcpPort
$wsB = Get-FreeTcpPort

$instA = $null
$instB = $null

try {
  $instA = Start-Instance -Name "gw-a" -HttpPort $httpA -WsPort $wsA -InstanceId "gw-a" -JarPath $JarPath
  $instB = Start-Instance -Name "gw-b" -HttpPort $httpB -WsPort $wsB -InstanceId "gw-b" -JarPath $JarPath

  Wait-Port "127.0.0.1" $instA.HttpPort $TimeoutMs
  Wait-Port "127.0.0.1" $instB.HttpPort $TimeoutMs
  Wait-Port "127.0.0.1" $instA.WsPort $TimeoutMs
  Wait-Port "127.0.0.1" $instB.WsPort $TimeoutMs

  $wsUrlA = "ws://127.0.0.1:$($instA.WsPort)/ws"
  $wsUrlB = "ws://127.0.0.1:$($instB.WsPort)/ws"
  $httpBaseA = "http://127.0.0.1:$($instA.HttpPort)"
  $httpBaseB = "http://127.0.0.1:$($instB.HttpPort)"

  $ts = Get-Date -Format "yyyyMMdd_HHmmss"
  $logDir = "logs/bp-multi"
  $absLogDir = Join-Path (Get-Location).Path $logDir
  New-Item -ItemType Directory -Force -Path $absLogDir | Out-Null

  powershell -ExecutionPolicy Bypass -File "scripts/ws-cluster-smoke-test/run.ps1" -WsUrlA $wsUrlA -WsUrlB $wsUrlB -HttpBaseA $httpBaseA -HttpBaseB $httpBaseB -TimeoutMs ([Math]::Max(8000, $TimeoutMs))
  if ($LASTEXITCODE -ne 0) { throw "ws-cluster smoke test failed (exit=$LASTEXITCODE)" }

  if ($RunMomentsSmoke) {
    $momOut = Join-Path $absLogDir "moments_smoke_${ts}.json"
    powershell -ExecutionPolicy Bypass -File "scripts/moments-smoke-test/run.ps1" -HttpBaseA $httpBaseA -HttpBaseB $httpBaseB -Password $Password -TimeoutMs ([Math]::Max(8000, $TimeoutMs)) | Set-Content -Encoding UTF8 -Path $momOut
    if ($LASTEXITCODE -ne 0) { throw "moments smoke test failed (exit=$LASTEXITCODE)" }
  }

  $dur = [Math]::Max(1, $DurationSeconds + 5)

  $memA = Join-Path $absLogDir "mem_gw-a_${ts}.csv"
  $memB = Join-Path $absLogDir "mem_gw-b_${ts}.csv"

  $jobScript = {
    param([int]$ProcId, [string]$CsvPath, [int]$Seconds)
    "pid,ts,workingSetMb" | Set-Content -Encoding UTF8 -Path $CsvPath
    for ($i = 0; $i -lt $Seconds; $i++) {
      try {
        $p = Get-Process -Id $ProcId -ErrorAction Stop
        $ts = [DateTime]::UtcNow.ToString("o")
        $mb = [Math]::Round($p.WorkingSet64 / 1MB, 2)
        "${ProcId},${ts},${mb}" | Add-Content -Encoding UTF8 -Path $CsvPath
      } catch {
        break
      }
      Start-Sleep -Seconds 1
    }
  }

  $metaOut = Join-Path $absLogDir "meta_${ts}.json"
  [pscustomobject]@{
    javaExe = $JavaExe
    jarPath = $JarPath
    instA = [pscustomobject]@{ name=$instA.Name; pid=$instA.Process.Id; httpPort=$instA.HttpPort; wsPort=$instA.WsPort; outLog=$instA.OutLog; errLog=$instA.ErrLog }
    instB = [pscustomobject]@{ name=$instB.Name; pid=$instB.Process.Id; httpPort=$instB.HttpPort; wsPort=$instB.WsPort; outLog=$instB.OutLog; errLog=$instB.ErrLog }
  } | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 -Path $metaOut

  $jobA = Start-Job -ScriptBlock $jobScript -ArgumentList @($instA.Process.Id, $memA, $dur)
  $jobB = Start-Job -ScriptBlock $jobScript -ArgumentList @($instB.Process.Id, $memB, $dur)

  try {
    $baseOut = Join-Path $absLogDir "load_single_e2e_base_${ts}.json"
    powershell -ExecutionPolicy Bypass -File "scripts/ws-load-test/run.ps1" -Mode single_e2e -JavaExe $JavaExe -JavacExe $javacResolved -WsUrls "${wsUrlA};${wsUrlB}" -RolePinned -Clients $Clients -DurationSeconds $DurationSeconds -WarmupMs $WarmupMs -MsgIntervalMs $MsgIntervalMs -UserBase $UserBase -BodyBytes $BodyBytes | Set-Content -Encoding UTF8 -Path $baseOut

    $slowOut = Join-Path $absLogDir "load_single_e2e_slow_${ts}.json"
    powershell -ExecutionPolicy Bypass -File "scripts/ws-load-test/run.ps1" -Mode single_e2e -JavaExe $JavaExe -JavacExe $javacResolved -WsUrls "${wsUrlA};${wsUrlB}" -RolePinned -Clients $Clients -DurationSeconds $DurationSeconds -WarmupMs $WarmupMs -MsgIntervalMs $MsgIntervalMs -UserBase ($UserBase + 1000000) -SlowConsumerPct $SlowConsumerPct -SlowConsumerDelayMs $SlowConsumerDelayMs -BodyBytes $BodyBytes | Set-Content -Encoding UTF8 -Path $slowOut

    $noReadOut = Join-Path $absLogDir "load_single_e2e_noread_${ts}.json"
    powershell -ExecutionPolicy Bypass -File "scripts/ws-load-test/run.ps1" -Mode single_e2e -JavaExe $JavaExe -JavacExe $javacResolved -WsUrls "${wsUrlA};${wsUrlB}" -RolePinned -Clients $Clients -DurationSeconds $DurationSeconds -WarmupMs $WarmupMs -MsgIntervalMs $MsgIntervalMs -UserBase ($UserBase + 2000000) -NoReadPct $NoReadPct -BodyBytes $BodyBytes | Set-Content -Encoding UTF8 -Path $noReadOut
  } finally {
    try { Stop-Job -Job $jobA -ErrorAction SilentlyContinue } catch { }
    try { Stop-Job -Job $jobB -ErrorAction SilentlyContinue } catch { }
    try { Remove-Job -Job $jobA -Force -ErrorAction SilentlyContinue } catch { }
    try { Remove-Job -Job $jobB -Force -ErrorAction SilentlyContinue } catch { }
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
