param(
  [string]$JarPath = "target/mini-im-0.0.1-SNAPSHOT.jar",
  [string]$JavaExe = "java",

  [int]$Instances = 5,
  [int]$BaseHttpPort = 8080,
  [int]$BaseWsPort = 9001,

  [ValidateSet("auto","push","notify","none")]
  [string]$GroupStrategyMode = "push",

  [switch]$SkipBuild,
  [switch]$SkipInfraCheck,
  [switch]$KeepProcesses,
  [switch]$SkipConnectLarge,
  [switch]$SkipGroup,
 
  [long]$UserBase = 20000000,
  [bool]$AutoUserBase = $true,
 
  [int]$PerfTraceSlowMs = 500,
  [double]$PerfTraceSampleRate = 0.01,
  [switch]$PerfTraceFull,

  [bool]$AutoTuneLocalThreads = $true,
  [int]$NettyBossThreads = 1,
  [int]$NettyWorkerThreads = 0,

  [int]$DbCorePoolSize = 8,
  [int]$DbMaxPoolSize = 32,
  [int]$DbQueueCapacity = 10000,

  [int]$PostDbCorePoolSize = 0,
  [int]$PostDbMaxPoolSize = 0,
  [int]$PostDbQueueCapacity = 0,

  [int]$AckCorePoolSize = 0,
  [int]$AckMaxPoolSize = 0,
  [int]$AckQueueCapacity = 0,

  [int]$JdbcMaxPoolSize = 0,
  [int]$JdbcMinIdle = 0,
  [int]$JdbcConnectionTimeoutMs = 0,

  [switch]$WsEncodeEnabled,

  [switch]$InboundQueueEnabled,
  [int]$InboundQueueMaxPendingPerConn = 2000,

  [bool]$SingleChatUpdatedAtDebounceEnabled = $true,
  [int]$SingleChatUpdatedAtDebounceWindowMs = 1000,
  [bool]$SingleChatUpdatedAtSyncUpdate = $false,

  [int]$DurationSmallSeconds = 60,
  [int]$DurationConnectSeconds = 30,
  [int]$WarmupMs = 1500,
  [int]$PingIntervalMs = 1000,
  [int]$MsgIntervalMs = 100,
  [int]$Inflight = 4,
  [switch]$OpenLoop,
  [int]$MaxInflightHard = 200,
  [long]$MaxValidE2eMs = 600000,
  [int]$LoadDrainMs = 1500,

  [bool]$AckBatchEnabled = $true,
  [int]$AckBatchWindowMs = 1000,
  [bool]$ResendAfterAuthEnabled = $false,

  [int]$Repeats = 3,
  [switch]$RunAckStress,
  [int]$AckStressMsgIntervalMs = 50,
  [int]$AckStressMaxInflightHard = 20000,
  [string]$AckStressTypes = "delivered,read",
  [int]$AckEveryN = 1,

  [switch]$EnableLargeE2e,
  [int]$LargeE2eRepeats = 1,
  [int]$LargeE2eDurationSeconds = 60,

  [switch]$SingleChatTwoPhaseEnabled,
  [switch]$SingleChatTwoPhaseDeliverBeforeSaved,
  [bool]$SingleChatTwoPhaseFailOpen = $true,
  [ValidateSet("redis","local")]
  [string]$SingleChatTwoPhaseMode = "redis",

  [int]$GroupClients = 200,
  [int]$GroupSenders = 20,
  [int]$GroupMsgIntervalMs = 50,
  [int]$GroupReceiverSamplePct = 30,

  [string]$Password = "p",

  [switch]$Run500k
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Avoid conda hook UnicodeEncodeError (GBK) in child PowerShell processes.
# This script spawns nested `powershell -File ...` processes; ensure Python/conda prints UTF-8.
$env:PYTHONIOENCODING = "utf-8"
$env:PYTHONUTF8 = "1"
$env:CONDA_NO_PLUGINS = "true"

if ($PerfTraceFull) {
  # Force full coverage: avoid "slow-only + sampling" bias in ws_perf.
  $PerfTraceSlowMs = 0
  $PerfTraceSampleRate = 1.0
}

if ($AutoUserBase -and -not $PSBoundParameters.ContainsKey("UserBase")) {
  # Avoid cross-run Redis idempotency pollution: clientMsgId is deterministic (userId-seq),
  # so reusing the same userBase may lead to "ACK(saved) but no delivery" in subsequent runs.
  $UserBase = 20000000 + ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() * 1000)
}
Write-Host ("[meta] AutoUserBase={0}, UserBase={1}" -f $AutoUserBase, $UserBase)

if ($AutoTuneLocalThreads) {
  $cpu = [Environment]::ProcessorCount
  $perInst = [Math]::Max(1, [Math]::Floor($cpu / [Math]::Max(1, $Instances)))
  $suggestNettyWorker = [Math]::Max(1, [Math]::Min(4, $perInst))

  # Keep total DB concurrency roughly stable across different instance counts on a single machine.
  # Otherwise, scaling instances can accidentally shrink total JDBC connections and trigger db queue timeouts.
  $targetDbTotal = [Math]::Max(8, [Math]::Min($cpu, 24))
  $suggestDb = [Math]::Ceiling($targetDbTotal / [Math]::Max(1, $Instances))
  $suggestDb = [Math]::Max(2, [Math]::Min(6, [int]$suggestDb))

  if (-not $PSBoundParameters.ContainsKey("NettyBossThreads")) { $NettyBossThreads = 1 }
  if (-not $PSBoundParameters.ContainsKey("NettyWorkerThreads") -and $NettyWorkerThreads -le 0) { $NettyWorkerThreads = $suggestNettyWorker }

  if (-not $PSBoundParameters.ContainsKey("DbCorePoolSize")) { $DbCorePoolSize = $suggestDb }
  if (-not $PSBoundParameters.ContainsKey("DbMaxPoolSize")) { $DbMaxPoolSize = $DbCorePoolSize }
  if (-not $PSBoundParameters.ContainsKey("DbQueueCapacity")) { $DbQueueCapacity = 2000 }

  if (-not $PSBoundParameters.ContainsKey("PostDbCorePoolSize") -and $PostDbCorePoolSize -le 0) { $PostDbCorePoolSize = [Math]::Max(1, [Math]::Min(2, $suggestDb)) }
  if (-not $PSBoundParameters.ContainsKey("PostDbMaxPoolSize") -and $PostDbMaxPoolSize -le 0) { $PostDbMaxPoolSize = $PostDbCorePoolSize }
  if (-not $PSBoundParameters.ContainsKey("PostDbQueueCapacity") -and $PostDbQueueCapacity -le 0) { $PostDbQueueCapacity = 2000 }

  if (-not $PSBoundParameters.ContainsKey("AckCorePoolSize") -and $AckCorePoolSize -le 0) { $AckCorePoolSize = 2 }
  if (-not $PSBoundParameters.ContainsKey("AckMaxPoolSize") -and $AckMaxPoolSize -le 0) { $AckMaxPoolSize = $AckCorePoolSize }
  if (-not $PSBoundParameters.ContainsKey("AckQueueCapacity") -and $AckQueueCapacity -le 0) { $AckQueueCapacity = 2000 }

  if (-not $PSBoundParameters.ContainsKey("JdbcMaxPoolSize") -and $JdbcMaxPoolSize -le 0) { $JdbcMaxPoolSize = $DbCorePoolSize }
  if (-not $PSBoundParameters.ContainsKey("JdbcMinIdle") -and $JdbcMinIdle -le 0) { $JdbcMinIdle = [Math]::Min($JdbcMaxPoolSize, 2) }

  Write-Host ("[meta] AutoTuneLocalThreads=true, cpu={0}, perInst={1}, nettyWorker={2}, db={3}, jdbcMax={4}, targetDbTotal={5}" -f $cpu, $perInst, $NettyWorkerThreads, $DbCorePoolSize, $JdbcMaxPoolSize, $targetDbTotal)
}

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

Ensure-EnvVarFromUser "IM_MYSQL_USERNAME"
Ensure-EnvVarFromUser "IM_MYSQL_PASSWORD"

function Assert-TcpOpen([string]$HostName, [int]$Port, [string]$Hint) {
  $ok = Test-TcpOpen -HostName $HostName -Port $Port -TimeoutMs 800
  if (-not $ok) { throw "Cannot connect to ${HostName}:${Port}. $Hint" }
}

function Wait-Port([string]$HostName, [int]$Port, [int]$TimeoutMs) {
  $deadline = [DateTime]::UtcNow.AddMilliseconds($TimeoutMs)
  while ([DateTime]::UtcNow -lt $deadline) {
    $ok = Test-TcpOpen -HostName $HostName -Port $Port -TimeoutMs 200
    if ($ok) { return }
    Start-Sleep -Milliseconds 200
  }
  throw "Timeout waiting for ${HostName}:${Port}"
}

function Test-TcpOpen([string]$HostName, [int]$Port, [int]$TimeoutMs) {
  $client = [System.Net.Sockets.TcpClient]::new()
  try {
    $ar = $client.BeginConnect($HostName, $Port, $null, $null)
    if (-not $ar.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) {
      return $false
    }
    $client.EndConnect($ar)
    return $client.Connected
  } catch {
    return $false
  } finally {
    try { $client.Close() } catch { }
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

$reservedPorts = New-Object 'System.Collections.Generic.HashSet[int]'

function Test-LocalPortInUse([int]$Port) {
  $listener = $null
  try {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $Port)
    $listener.Start()
    return $false
  } catch {
    return $true
  } finally {
    if ($null -ne $listener) {
      try { $listener.Stop() } catch { }
    }
  }
}

function Pick-Port([int]$Preferred, $Reserved) {
  if ($null -eq $Reserved) { $Reserved = $reservedPorts }

  if (-not $Reserved.Contains($Preferred)) {
    $inUse = Test-LocalPortInUse $Preferred
    if (-not $inUse) {
      [void]$Reserved.Add($Preferred)
      return $Preferred
    }
  }

  while ($true) {
    $p = Get-FreeTcpPort
    if ($Reserved.Contains($p)) { continue }
    $inUse = Test-LocalPortInUse $p
    if ($inUse) { continue }
    [void]$Reserved.Add($p)
    return $p
  }
}

if (-not $SkipInfraCheck) {
  Assert-TcpOpen "127.0.0.1" 3306 "MySQL must be running"
  Assert-TcpOpen "127.0.0.1" 6379 "Redis must be running"
}

if (-not $SkipBuild) {
  mvn -q -DskipTests package
  if ($LASTEXITCODE -ne 0) { throw "mvn package failed (exit=$LASTEXITCODE)" }
}
if (-not (Test-Path -LiteralPath $JarPath)) { throw "Jar not found: $JarPath" }

$ts = Get-Date -Format "yyyyMMdd_HHmmss"
$runDir = Join-Path "logs" ("ws-cluster-5x-test_" + $ts)
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

function Start-Instance([int]$Index) {
  $name = "gw-" + ($Index + 1)
  $httpPort = Pick-Port ($BaseHttpPort + $Index) $reservedPorts
  $wsPort = Pick-Port ($BaseWsPort + $Index) $reservedPorts
  $instanceId = $name
  $outLog = Join-Path $runDir ("${name}.out.log")
  $errLog = Join-Path $runDir ("${name}.err.log")
  Write-Host ("[{0}] ports: http={1}, ws={2}" -f $name, $httpPort, $wsPort)

  $argList = New-Object 'System.Collections.Generic.List[string]'
  [void]$argList.Add("-Dfile.encoding=UTF-8")
  [void]$argList.Add("-jar")
  [void]$argList.Add($JarPath)
  [void]$argList.Add("--server.port=$httpPort")

  if ($JdbcMaxPoolSize -gt 0) { [void]$argList.Add("--spring.datasource.hikari.maximum-pool-size=$JdbcMaxPoolSize") }
  if ($JdbcMinIdle -gt 0) { [void]$argList.Add("--spring.datasource.hikari.minimum-idle=$JdbcMinIdle") }
  if ($JdbcConnectionTimeoutMs -gt 0) { [void]$argList.Add("--spring.datasource.hikari.connection-timeout=$JdbcConnectionTimeoutMs") }

  [void]$argList.Add("--im.gateway.ws.host=127.0.0.1")
  [void]$argList.Add("--im.gateway.ws.port=$wsPort")
  [void]$argList.Add("--im.gateway.ws.instance-id=$instanceId")
  [void]$argList.Add("--im.gateway.ws.boss-threads=$NettyBossThreads")
  if ($NettyWorkerThreads -gt 0) { [void]$argList.Add("--im.gateway.ws.worker-threads=$NettyWorkerThreads") }
  [void]$argList.Add("--im.group-chat.strategy.mode=$GroupStrategyMode")
  [void]$argList.Add("--im.gateway.ws.perf-trace.enabled=true")
  [void]$argList.Add("--im.gateway.ws.perf-trace.slow-ms=$PerfTraceSlowMs")
  [void]$argList.Add("--im.gateway.ws.perf-trace.sample-rate=$PerfTraceSampleRate")
  [void]$argList.Add("--im.executors.db.core-pool-size=$DbCorePoolSize")
  [void]$argList.Add("--im.executors.db.max-pool-size=$DbMaxPoolSize")
  [void]$argList.Add("--im.executors.db.queue-capacity=$DbQueueCapacity")
  if ($PostDbCorePoolSize -gt 0) { [void]$argList.Add("--im.executors.post-db.core-pool-size=$PostDbCorePoolSize") }
  if ($PostDbMaxPoolSize -gt 0) { [void]$argList.Add("--im.executors.post-db.max-pool-size=$PostDbMaxPoolSize") }
  if ($PostDbQueueCapacity -gt 0) { [void]$argList.Add("--im.executors.post-db.queue-capacity=$PostDbQueueCapacity") }
  if ($AckCorePoolSize -gt 0) { [void]$argList.Add("--im.executors.ack.core-pool-size=$AckCorePoolSize") }
  if ($AckMaxPoolSize -gt 0) { [void]$argList.Add("--im.executors.ack.max-pool-size=$AckMaxPoolSize") }
  if ($AckQueueCapacity -gt 0) { [void]$argList.Add("--im.executors.ack.queue-capacity=$AckQueueCapacity") }
  [void]$argList.Add("--im.gateway.ws.encode.enabled=$($WsEncodeEnabled.IsPresent.ToString().ToLower())")
  [void]$argList.Add("--im.gateway.ws.inbound-queue.enabled=$($InboundQueueEnabled.IsPresent.ToString().ToLower())")
  [void]$argList.Add("--im.gateway.ws.inbound-queue.max-pending-per-conn=$InboundQueueMaxPendingPerConn")
  [void]$argList.Add("--im.gateway.ws.single-chat.updated-at.debounce-enabled=$($SingleChatUpdatedAtDebounceEnabled.ToString().ToLower())")
  [void]$argList.Add("--im.gateway.ws.single-chat.updated-at.debounce-window-ms=$SingleChatUpdatedAtDebounceWindowMs")
  [void]$argList.Add("--im.gateway.ws.single-chat.updated-at.sync-update=$($SingleChatUpdatedAtSyncUpdate.ToString().ToLower())")
  [void]$argList.Add("--im.gateway.ws.ack.batch-enabled=$($AckBatchEnabled.ToString().ToLower())")
  [void]$argList.Add("--im.gateway.ws.ack.batch-window-ms=$AckBatchWindowMs")
  [void]$argList.Add("--im.gateway.ws.resend.after-auth-enabled=$($ResendAfterAuthEnabled.ToString().ToLower())")
  [void]$argList.Add("--im.gateway.ws.single-chat.two-phase.enabled=$($SingleChatTwoPhaseEnabled.IsPresent.ToString().ToLower())")
  [void]$argList.Add("--im.gateway.ws.single-chat.two-phase.deliver-before-saved=$($SingleChatTwoPhaseDeliverBeforeSaved.IsPresent.ToString().ToLower())")
  [void]$argList.Add("--im.gateway.ws.single-chat.two-phase.fail-open=$($SingleChatTwoPhaseFailOpen.ToString().ToLower())")
  [void]$argList.Add("--im.gateway.ws.single-chat.two-phase.mode=$SingleChatTwoPhaseMode")

  $args = $argList.ToArray()
  $tail = ($args | Select-Object -Last 5) -join " | "
  Write-Host ("[{0}] java args count={1}, tail={2}" -f $name, $args.Length, $tail)

  $p = Start-Process -FilePath $JavaExe -ArgumentList $args -PassThru -NoNewWindow -WorkingDirectory (Get-Location).Path -RedirectStandardOutput $outLog -RedirectStandardError $errLog
  try {
    $cmd = (Get-CimInstance Win32_Process -Filter ("ProcessId = " + $p.Id) | Select-Object -First 1).CommandLine
    if ($cmd) {
      $cmdPath = Join-Path $runDir ("${name}.cmdline.txt")
      $cmd | Set-Content -Encoding UTF8 -Path $cmdPath
    }
  } catch { }
  return [pscustomobject]@{
    Name = $name
    Process = $p
    HttpPort = $httpPort
    WsPort = $wsPort
    OutLog = $outLog
    ErrLog = $errLog
  }
}

$instList = @()
try {
  for ($i = 0; $i -lt $Instances; $i++) {
    $inst = Start-Instance $i
    $instList += $inst
  }

  foreach ($inst in $instList) {
    Wait-Port "127.0.0.1" $inst.HttpPort 90000
    Wait-Port "127.0.0.1" $inst.WsPort 90000
  }

  $wsUrls = ($instList | ForEach-Object { "ws://127.0.0.1:$($_.WsPort)/ws" }) -join ";"
  $http0 = "http://127.0.0.1:$($instList[0].HttpPort)"
  $ws0 = "ws://127.0.0.1:$($instList[0].WsPort)/ws"
  $rolePinnedSplat = @{}
  if ($instList.Count -ge 2) { $rolePinnedSplat = @{ RolePinned = $true } }

  $results = [ordered]@{
    ok = $true
    runDir = $runDir
    instances = ($instList | ForEach-Object { [ordered]@{ name=$_.Name; httpPort=$_.HttpPort; wsPort=$_.WsPort } })
    groupStrategyMode = $GroupStrategyMode
    levels = @()
  }

function Run-Step([string]$Name, [scriptblock]$Fn) {
  $outPath = Join-Path $runDir ($Name + ".json")
  $raw = & $Fn
  $raw | Set-Content -Encoding UTF8 -Path $outPath
  return $outPath
}

function Run-StepRepeated([string]$Name, [int]$Times, [scriptblock]$Fn, [scriptblock]$Extract) {
  $items = @()
  $innerFn = $Fn
  for ($i = 1; $i -le $Times; $i++) {
    $outPath = Run-Step ("${Name}_r${i}") { & $innerFn $i }
    $obj = Get-Content -LiteralPath $outPath -Encoding UTF8 -Raw | ConvertFrom-Json
    $items += $obj
  }
  $avg = & $Extract $items
  $avgPath = Join-Path $runDir ("${Name}_avg.json")
  ($avg | ConvertTo-Json -Depth 8) | Set-Content -Encoding UTF8 -Path $avgPath
  return $avgPath
}

  # Smoke: pick first two instances (only when Instances >= 2)
  if ($instList.Count -ge 2) {
    try {
      $smokePath = Run-Step "smoke_cluster_2x" {
        & "scripts/ws-cluster-smoke-test/run.ps1" -WsUrlA $ws0 -WsUrlB ("ws://127.0.0.1:$($instList[1].WsPort)/ws") -HttpBaseA $http0 -HttpBaseB ("http://127.0.0.1:$($instList[1].HttpPort)") -Password $Password -TimeoutMs 20000 -GroupStrategyMode $GroupStrategyMode
      }
      $results.smoke = $smokePath
    } catch {
      $results.ok = $false
      $results.smokeError = $_.Exception.Message
    }
  } else {
    $results.smokeSkipped = $true
    $results.smokeSkippedReason = "Instances < 2"
  }

  $levels = @(500, 5000, 50000)
  if ($SkipConnectLarge.IsPresent) { $levels = @(500, 5000) }
  if ($Run500k) { $levels += 500000 }
  foreach ($n in $levels) {
    $level = [ordered]@{ clients = $n }

    try {
      $connectPath = Run-Step ("connect_" + $n) {
        & "scripts/ws-load-test/run.ps1" -Mode connect -WsUrls $wsUrls @rolePinnedSplat -Clients $n -DurationSeconds $DurationConnectSeconds -WarmupMs $WarmupMs -UserBase $UserBase -DrainMs 0
      }
      $level.connect = $connectPath
    } catch {
      $level.connectError = $_.Exception.Message
    }

    if ($n -le 5000) {
      try {
        $pingPath = Run-Step ("ping_" + $n) {
          & "scripts/ws-load-test/run.ps1" -Mode ping -WsUrls $wsUrls @rolePinnedSplat -Clients $n -DurationSeconds $DurationSmallSeconds -WarmupMs $WarmupMs -PingIntervalMs $PingIntervalMs -UserBase $UserBase -DrainMs 0
        }
        $level.ping = $pingPath
      } catch {
        $level.pingError = $_.Exception.Message
      }
    }

    $canE2e = ($n % 2 -eq 0) -and (($n -le 5000) -or $EnableLargeE2e)
    if ($canE2e) {
      try {
        $repN = $Repeats
        $durN = $DurationSmallSeconds
        if ($n -gt 5000) {
          $repN = [Math]::Max(1, $LargeE2eRepeats)
          $durN = [Math]::Max(10, $LargeE2eDurationSeconds)
        }

        $e2ePath = Run-StepRepeated ("single_e2e_" + $n) $repN {
          param($rep)
          $olSplat = @{}
          if ($OpenLoop) { $olSplat = @{ OpenLoop = $true; MaxInflightHard = $MaxInflightHard } }
          $ub = $UserBase + ($rep * 1000000)
          & "scripts/ws-load-test/run.ps1" -Mode single_e2e -WsUrls $wsUrls @rolePinnedSplat -Clients $n -DurationSeconds $durN -WarmupMs $WarmupMs -MsgIntervalMs $MsgIntervalMs -UserBase $ub -Inflight $Inflight -MaxValidE2eMs $MaxValidE2eMs -DrainMs $LoadDrainMs @olSplat
        } {
          param($items)
          $p50 = ($items | ForEach-Object { $_.singleChat.e2eMs.p50 } | Measure-Object -Average).Average
          $p95 = ($items | ForEach-Object { $_.singleChat.e2eMs.p95 } | Measure-Object -Average).Average
          $p99 = ($items | ForEach-Object { $_.singleChat.e2eMs.p99 } | Measure-Object -Average).Average

          $acceptedP50 = ($items | ForEach-Object { $_.singleChat.acceptedMs.p50 } | Where-Object { $null -ne $_ } | Measure-Object -Average).Average
          $acceptedP95 = ($items | ForEach-Object { $_.singleChat.acceptedMs.p95 } | Where-Object { $null -ne $_ } | Measure-Object -Average).Average
          $acceptedP99 = ($items | ForEach-Object { $_.singleChat.acceptedMs.p99 } | Where-Object { $null -ne $_ } | Measure-Object -Average).Average

          $savedP50 = ($items | ForEach-Object { $_.singleChat.savedMs.p50 } | Where-Object { $null -ne $_ } | Measure-Object -Average).Average
          $savedP95 = ($items | ForEach-Object { $_.singleChat.savedMs.p95 } | Where-Object { $null -ne $_ } | Measure-Object -Average).Average
          $savedP99 = ($items | ForEach-Object { $_.singleChat.savedMs.p99 } | Where-Object { $null -ne $_ } | Measure-Object -Average).Average

          $hasAccepted = ($null -ne $acceptedP50) -or ($null -ne $acceptedP95) -or ($null -ne $acceptedP99)
          $hasSaved = ($null -ne $savedP50) -or ($null -ne $savedP95) -or ($null -ne $savedP99)
          [ordered]@{
            repeats = $items.Count
            openLoop = $items[0].openLoop
            msgIntervalMs = $items[0].msgIntervalMs
            attemptedPerSecAvg = ($items | ForEach-Object { $_.singleChat.attemptedPerSec } | Measure-Object -Average).Average
            sentPerSecAvg = ($items | ForEach-Object { $_.singleChat.sentPerSec } | Measure-Object -Average).Average
            ackAcceptedPerSecAvg = ($items | ForEach-Object { ($_.singleChat.ackAccepted / $_.durationSeconds) } | Measure-Object -Average).Average
            ackSavedPerSecAvg = ($items | ForEach-Object { ($_.singleChat.ackSaved / $_.durationSeconds) } | Measure-Object -Average).Average
            recvPerSecAvg = ($items | ForEach-Object { ($_.singleChat.recv / $_.durationSeconds) } | Measure-Object -Average).Average
            wsErrorAvg = ($items | ForEach-Object { $_.errors.wsError } | Measure-Object -Average).Average
            e2eInvalidAvg = ($items | ForEach-Object { $_.singleChat.e2eInvalid } | Measure-Object -Average).Average
            e2eMs = [ordered]@{ p50 = [math]::Round($p50,2); p95 = [math]::Round($p95,2); p99 = [math]::Round($p99,2) }
            acceptedMs = if ($hasAccepted) { [ordered]@{ p50=[math]::Round($acceptedP50,2); p95=[math]::Round($acceptedP95,2); p99=[math]::Round($acceptedP99,2) } } else { $null }
            savedMs = if ($hasSaved) { [ordered]@{ p50=[math]::Round($savedP50,2); p95=[math]::Round($savedP95,2); p99=[math]::Round($savedP99,2) } } else { $null }
            dupAvg = ($items | ForEach-Object { $_.singleChat.dup } | Measure-Object -Average).Average
            reorderAvg = ($items | ForEach-Object { $_.singleChat.reorder } | Measure-Object -Average).Average
            reorderByFromAvg = ($items | ForEach-Object { $_.singleChat.reorderByFrom } | Measure-Object -Average).Average
            reorderByServerMsgIdAvg = ($items | ForEach-Object { $_.singleChat.reorderByServerMsgId } | Measure-Object -Average).Average
          }
        }
        $level.singleE2e = $e2ePath
      } catch {
        $level.singleE2eError = $_.Exception.Message
      }
    }

    # ACK_STRESS 会强烈占用 DB/队列并影响后续 level 的 E2E；因此放到 levels 循环外单独跑（默认仅测 5000）。

    $results.levels += $level
  }

  if ($RunAckStress) {
    try {
      $n = 5000
      $ackPath = Run-StepRepeated ("ack_stress_" + $n) $Repeats {
        param($rep)
        $olSplat = @{}
        if ($OpenLoop) { $olSplat = @{ OpenLoop = $true; MaxInflightHard = $AckStressMaxInflightHard } }
        $ub = $UserBase + (9000000 + $rep * 1000000)
        & "scripts/ws-load-test/run.ps1" -Mode ack_stress -WsUrls $wsUrls @rolePinnedSplat -Clients $n -DurationSeconds $DurationSmallSeconds -WarmupMs $WarmupMs -MsgIntervalMs $AckStressMsgIntervalMs -UserBase $ub -Inflight $Inflight -MaxValidE2eMs $MaxValidE2eMs -AckStressTypes $AckStressTypes -AckEveryN $AckEveryN -DrainMs $LoadDrainMs @olSplat
      } {
        param($items)
        [ordered]@{
          repeats = $items.Count
          clients = 5000
          msgIntervalMs = $items[0].msgIntervalMs
          ackStressTypes = $items[0].ackStress.types
          ackEveryN = $items[0].ackStress.ackEveryN
          sentPerSecAvg = ($items | ForEach-Object { $_.ackStress.sentPerSec } | Measure-Object -Average).Average
          acksSentPerSecAvg = ($items | ForEach-Object { ($_.ackStress.acksSent / $_.durationSeconds) } | Measure-Object -Average).Average
          wsErrorAvg = ($items | ForEach-Object { $_.errors.wsError } | Measure-Object -Average).Average
        }
      }
      $results.ackStress = $ackPath
    } catch {
      $results.ackStressError = $_.Exception.Message
    }
  }

  if (-not $SkipGroup) {
    try {
      $groupPath = Run-Step "group_push_e2e" {
        & "scripts/ws-group-load-test/run.ps1" -WsUrls $wsUrls -HttpBase $http0 -Clients $GroupClients -Senders $GroupSenders -DurationSeconds $DurationSmallSeconds -WarmupMs $WarmupMs -MsgIntervalMs $GroupMsgIntervalMs -Password $Password -ReceiverSamplePct $GroupReceiverSamplePct
      }
      $results.group = $groupPath
    } catch {
      $results.groupError = $_.Exception.Message
    }
  } else {
    $results.groupSkipped = $true
  }

  # Perf parse: per-instance summaries
  try {
    $perfByInstance = [ordered]@{}
    for ($i = 0; $i -lt $instList.Count; $i++) {
      $gw = $i + 1
      $perfPath = Run-Step ("ws_perf_summary_gw" + $gw) {
        & "scripts/ws-perf-tools/parse-ws-perf.ps1" -LogPath $instList[$i].OutLog -MaxLines 450000
      }
      $perfByInstance[("gw" + $gw)] = $perfPath
    }
    $results.perf = $perfByInstance["gw1"]
    $results.perfByInstance = $perfByInstance
  } catch {
    $results.perfError = $_.Exception.Message
  }

  $resultsPath = Join-Path $runDir "summary.json"
  ($results | ConvertTo-Json -Depth 8) | Set-Content -Encoding UTF8 -Path $resultsPath

  Get-Content -LiteralPath $resultsPath -Encoding UTF8
} finally {
  if (-not $KeepProcesses) {
    foreach ($inst in $instList) {
      try { Stop-Process -Id $inst.Process.Id -Force -ErrorAction SilentlyContinue } catch { }
    }
  }
}
