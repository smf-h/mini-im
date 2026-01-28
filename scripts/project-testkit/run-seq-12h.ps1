param(
  [string]$ArtifactsRoot = "artifacts/project-testkit/20260125_213233",
  [string]$JarPath = "target/mini-im-0.0.1-SNAPSHOT.jar",
  [string]$JavaExe = "java",
  [string]$MysqlHost = "127.0.0.1",
  [int]$MysqlPort = 3306,
  [string]$MysqlDatabase = "",

  [string]$WsUrls = "ws://127.0.0.1:9001/ws;ws://127.0.0.1:9002/ws",
  [string]$HttpBase = "http://127.0.0.1:8080",

  [int]$HttpPortA = 8080,
  [int]$WsPortA = 9001,
  [string]$InstanceIdA = "gw-a",
  [int]$HttpPortB = 8082,
  [int]$WsPortB = 9002,
  [string]$InstanceIdB = "gw-b",

  [int[]]$Levels = @(1000, 2000, 3000, 4000),
  [string]$SegmentDuration = "1h",
  [int]$GroupDurationSeconds = 3600,

  [int]$PingIntervalMs = 30000,
  [int]$MsgIntervalMs = 1000,
  [int]$WarmupMs = 1000,
  [int]$StartupTimeoutMs = 240000,

  [switch]$SkipInfraCheck,
  [switch]$SkipBuild,
  [switch]$KeepProcesses
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Log([string]$msg) {
  $ts = [DateTime]::UtcNow.ToString("o")
  Write-Output "[${ts}] ${msg}"
}

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

function Resolve-JavaExe([string]$javaExe) {
  if (-not $javaExe -or $javaExe.Trim().Length -eq 0) { $javaExe = "java" }

  if (Test-Path -LiteralPath $javaExe) { return $javaExe }

  $resolved = $javaExe
  try {
    $cmd = Get-Command $javaExe -ErrorAction Stop
    if ($cmd -and $cmd.Path) { $resolved = $cmd.Path }
  } catch {
    # ignore
  }

  $needsFix = ($javaExe.Trim().ToLower() -eq "java") -or ($resolved -match "\\\\Oracle\\\\Java\\\\javapath\\\\java\\.exe$") -or ($resolved -match "\\\\javapath\\\\java\\.exe$")
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

function Ensure-EnvVarFromUser([string]$Name) {
  $item = Get-Item -Path ("Env:" + $Name) -ErrorAction SilentlyContinue
  $cur = if ($null -eq $item) { "" } else { $item.Value }
  if (-not [string]::IsNullOrWhiteSpace($cur)) { return }
  $userVal = [Environment]::GetEnvironmentVariable($Name, "User")
  if (-not [string]::IsNullOrWhiteSpace($userVal)) {
    Set-Item -Path ("Env:" + $Name) -Value $userVal
  }
}

function Assert-TcpOpen([string]$HostName, [int]$Port, [string]$Hint) {
  $ok = (Test-NetConnection -ComputerName $HostName -Port $Port -WarningAction SilentlyContinue).TcpTestSucceeded
  if (-not $ok) { throw "Cannot connect to ${HostName}:${Port}. $Hint" }
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

function Resolve-MySqlExe() {
  try {
    $cmd = Get-Command mysql -ErrorAction Stop
    if ($cmd -and $cmd.Path) { return $cmd.Path }
  } catch {
    # ignore
  }
  $candidates = @(
    "C:\\Program Files\\MySQL\\MySQL Server 9.2\\bin\\mysql.exe",
    "C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe"
  )
  foreach ($c in $candidates) {
    if (Test-Path -LiteralPath $c) { return $c }
  }
  return "mysql"
}

function Ensure-Database([string]$mysqlExe, [string]$hostName, [int]$port, [string]$dbName) {
  $u = $env:IM_MYSQL_USERNAME
  if ([string]::IsNullOrWhiteSpace($u)) { $u = "root" }
  $pwd = $env:IM_MYSQL_PASSWORD

  $sql = "CREATE DATABASE IF NOT EXISTS ``${dbName}`` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
  $args = @("-h", $hostName, "-P", "$port", "-u", $u, "--protocol=tcp", "--default-character-set=utf8mb4")
  if (-not [string]::IsNullOrWhiteSpace($pwd)) { $args += "--password=$pwd" }
  $args += @("-e", $sql)

  $old = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  try {
    & $mysqlExe @args 1>$null 2>$null
    $code = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $old
  }

  if ($code -ne 0) { throw "mysql create database failed (exit=$code)" }
}

function Mask([string]$s) {
  if ($null -eq $s) { return "" }
  $jwt = [Environment]::GetEnvironmentVariable("IM_AUTH_JWT_SECRET", "Process")
  if (-not [string]::IsNullOrWhiteSpace($jwt)) { return $s.Replace($jwt, "<IM_AUTH_JWT_SECRET>") }
  return $s
}

function Get-NextExperimentIndex([string]$root) {
  $max = -1
  $dirs = Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue
  foreach ($d in $dirs) {
    if ($d.Name -match "^E(\d{2})$") {
      $n = [int]$Matches[1]
      if ($n -gt $max) { $max = $n }
    }
  }
  return ($max + 1)
}

function New-Experiment([string]$root, [int]$idx) {
  $name = ("E{0:d2}" -f $idx)
  $dir = Join-Path $root $name
  New-Item -ItemType Directory -Force -Path (Join-Path $dir "raw") | Out-Null
  New-Item -ItemType Directory -Force -Path (Join-Path $dir "logs") | Out-Null
  New-Item -ItemType Directory -Force -Path (Join-Path $dir "report") | Out-Null
  return $dir
}

function Write-ExperimentMd([string]$path, [hashtable]$meta) {
  $tplPath = "scripts/project-testkit/templates/experiment.md.tpl"
  $tpl = Get-Content -Raw -Encoding UTF8 -LiteralPath $tplPath

  $expId = [string]$meta.name
  $scenario = [string]$meta.scenario
  $level = [string]$meta.level

  $hyp = "online/level changes -> observe p99 and errors"
  $change = "load: scenario=$scenario; level=$level; duration=$([string]$meta.duration)"
  $why = "baseline run for later one-change-per-iteration optimization"
  $planRef = "Plan: artifacts/project-testkit/20260125_213233/plan.md"
  $guardrails = "dup/reorder/loss=0; ws_auth_fail~=0; error rate should not regress"

  $before = "TBD (baseline stair run; no before/after)"
  $after = "p99=$([string]$meta.p99); connectFail=$([string]$meta.connectFail); authFail=$([string]$meta.authFail); serverError=$([string]$meta.serverError); sentPerSec=$([string]$meta.sentPerSec); recvPerSec=$([string]$meta.recvPerSec)"
  $delta = "TBD (no before/after)"
  $benefit = "collect tail-latency + errors at this load"
  $cost = "time + resource usage"
  $need = "pick a fixed level for code optimization iterations"
  $decision = if ($meta.status -eq "ok") { "keep (baseline evidence)" } elseif ($meta.status -eq "skipped") { "skipped" } else { "need more evidence / attribution first" }
  $evidence = @((Mask([string]$meta.rawPath)), (Mask([string]$meta.logPath))) -join "; "

  $out = $tpl
  $out = $out.Replace("{{ExperimentId}}", $expId)
  $out = $out.Replace("{{Hypothesis}}", $hyp)
  $out = $out.Replace("{{Change}}", $change)
  $out = $out.Replace("{{Reason}}", $why)
  $out = $out.Replace("{{PlanRef}}", $planRef)
  $out = $out.Replace("{{Guardrails}}", $guardrails)
  $out = $out.Replace("{{Before}}", $before)
  $out = $out.Replace("{{After}}", $after)
  $out = $out.Replace("{{Delta}}", $delta)
  $out = $out.Replace("{{Benefit}}", $benefit)
  $out = $out.Replace("{{Cost}}", $cost)
  $out = $out.Replace("{{Necessity}}", $need)
  $out = $out.Replace("{{Decision}}", $decision)
  $out = $out.Replace("{{Evidence}}", $evidence)
  $out = $out.Replace("{{Trigger}}", [string]$meta.trigger)
  $out = $out.Replace("{{Suspected}}", [string]$meta.suspected)
  $out = $out.Replace("{{Next}}", [string]$meta.next)

  $out | Set-Content -Encoding UTF8 -Path $path
}

function Ensure-SummaryTemplate([string]$path) {
  if (Test-Path -LiteralPath $path) {
    $raw = Get-Content -Raw -Encoding UTF8 -LiteralPath $path
    if ($raw -match "<!-- ROWS -->") { return }
  }

  $tplPath = "scripts/project-testkit/templates/summary.md.tpl"
  $tpl = Get-Content -Raw -Encoding UTF8 -LiteralPath $tplPath
  $tpl = $tpl.Replace("{{OverallPass}}", "TBD")
  $tpl = $tpl.Replace("{{OverallEvidence}}", "TBD")
  $tpl = $tpl.Replace("{{BestExperiment}}", "TBD")
  $tpl = $tpl.Replace("{{BestBenefit}}", "TBD")
  $tpl = $tpl.Replace("{{BestCost}}", "TBD")
  $tpl = $tpl.Replace("{{WorstExperiment}}", "TBD")
  $tpl = $tpl.Replace("{{WorstReason}}", "TBD")
  $tpl = $tpl.Replace("{{Regression}}", "TBD")
  $tpl = $tpl.Replace("{{RegressionHandling}}", "TBD")
  $tpl = $tpl.Replace("{{NextHypothesis}}", "TBD")
  $tpl = $tpl.Replace("{{NextAction}}", "TBD")
  $tpl = $tpl.Replace("{{PlanRef}}", "artifacts/project-testkit/20260125_213233/plan.md")
  $tpl = $tpl.Replace("{{RunnerRef}}", "scripts/project-testkit/run-seq-12h.ps1")

  $tpl | Set-Content -Encoding UTF8 -Path $path
}

function Append-SummaryRow([string]$path, [hashtable]$meta) {
  Ensure-SummaryTemplate $path

  $exp = $meta.name
  $scenario = $meta.scenario
  $level = $meta.level

  $hyp = "online/level up -> observe tail + errors"
  $change = "load:$scenario@$level"
  $p99 = if ($null -eq $meta.p99 -or [string]::IsNullOrWhiteSpace([string]$meta.p99)) { "TBD" } else { "p99=$($meta.p99)" }
  $err = @()
  if ($meta.connectFail -ne $null) { $err += "connectFail=$($meta.connectFail)" }
  if ($meta.authFail -ne $null) { $err += "authFail=$($meta.authFail)" }
  if ($meta.serverError -ne $null) { $err += "serverError=$($meta.serverError)" }
  $errCell = if ($err.Count -eq 0) { "TBD" } else { ($err -join ",") }
  $tpt = @()
  if ($meta.sentPerSec -ne $null) { $tpt += "sent/s=$($meta.sentPerSec)" }
  if ($meta.recvPerSec -ne $null) { $tpt += "recv/s=$($meta.recvPerSec)" }
  $tptCell = if ($tpt.Count -eq 0) { "TBD" } else { ($tpt -join ",") }
  $cg = "TBD"
  $cons = "dup/reorder/loss=TBD"
  $risk = "time"
  $conclusion = if ($meta.status -eq "ok") { "keep" } elseif ($meta.status -eq "skipped") { "skipped" } else { "problem(needs attribution)" }

  $row = "| $exp | $hyp | $change | $p99 | $errCell | $tptCell | $cg | $cons | $risk | $conclusion |"

  $raw = Get-Content -Raw -Encoding UTF8 -LiteralPath $path
  $updated = $raw -replace "(?m)^<!-- ROWS -->$", ($row + "`r`n<!-- ROWS -->")
  $updated | Set-Content -Encoding UTF8 -Path $path
}

function Try-ParseDouble([object]$v) {
  if ($null -eq $v) { return $null }
  try {
    $s = [string]$v
    if ([string]::IsNullOrWhiteSpace($s)) { return $null }
    return [double]::Parse($s, [System.Globalization.CultureInfo]::InvariantCulture)
  } catch {
    return $null
  }
}

function Finalize-Summary([string]$artifactsRoot, [string]$summaryPath) {
  if (-not (Test-Path -LiteralPath $summaryPath)) { return }

  $runs = @()
  $dirs = Get-ChildItem -LiteralPath $artifactsRoot -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -match "^E(\\d{2})$" }
  foreach ($d in $dirs) {
    $runJson = Join-Path $d.FullName "run.json"
    if (-not (Test-Path -LiteralPath $runJson)) { continue }
    try {
      $raw = Get-Content -Raw -Encoding UTF8 -LiteralPath $runJson
      if ([string]::IsNullOrWhiteSpace($raw)) { continue }
      $obj = $raw | ConvertFrom-Json
      $runs += $obj
    } catch {
      # ignore
    }
  }

  $problem = @($runs | Where-Object { $_.status -eq "problem" })
  $overallPass = if ($problem.Count -gt 0) { "NO" } else { "YES" }

  $p99Vals = @()
  foreach ($r in $runs) {
    $p = Try-ParseDouble $r.p99
    if ($null -ne $p) { $p99Vals += $p }
  }
  $p99Min = if ($p99Vals.Count -gt 0) { ($p99Vals | Measure-Object -Minimum).Minimum } else { $null }
  $p99Max = if ($p99Vals.Count -gt 0) { ($p99Vals | Measure-Object -Maximum).Maximum } else { $null }

  $evidence = if ($null -ne $p99Min -and $null -ne $p99Max) { "p99 range: ${p99Min}~${p99Max}" } else { "p99: TBD" }

  $best = $runs | Where-Object { $_.status -eq "ok" -and (Try-ParseDouble $_.p99) -ne $null } | Sort-Object { [double]$_.p99 } | Select-Object -First 1
  $worst = $runs | Where-Object { $_.status -eq "ok" -and (Try-ParseDouble $_.p99) -ne $null } | Sort-Object { -[double]$_.p99 } | Select-Object -First 1
  if ($null -eq $worst -and $problem.Count -gt 0) { $worst = $problem[0] }

  $bestExp = if ($best) { $best.name } else { "TBD" }
  $worstExp = if ($worst) { $worst.name } else { "TBD" }
  $bestBenefit = if ($best) { "lowest p99 in baseline" } else { "TBD" }
  $bestCost = "none (baseline)"
  $worstReason = if ($problem.Count -gt 0) { "errors or tail spike" } else { "highest p99 in baseline" }

  $regression = if ($problem.Count -gt 0) { ($problem | ForEach-Object { "$($_.name):$($_.scenario)@$($_.level)" } | Select-Object -First 5) -join "; " } else { "none observed" }
  $handling = if ($problem.Count -gt 0) { "auto switch scenario/level; keep evidence" } else { "n/a" }
  $nextHyp = "pick the worst p99 segment and profile (JFR/async-profiler), then do one-change-per-iteration"
  $nextAct = "run N=3 at fixed level for the worst scenario; then apply minimal code/config change and re-run N=3"

  $text = Get-Content -Raw -Encoding UTF8 -LiteralPath $summaryPath
  $lines = $text -split "`r?`n"
  $out = New-Object System.Collections.Generic.List[string]
  $i = 0
  while ($i -lt $lines.Count) {
    $line = $lines[$i]
    if ($line -match "^1\\)") { $out.Add("1) overallPass=$overallPass; evidence=$evidence"); $i++; continue }
    if ($line -match "^2\\)") { $out.Add("2) bestExperiment=$bestExp; benefit=$bestBenefit; cost=$bestCost"); $i++; continue }
    if ($line -match "^3\\)") { $out.Add("3) worstExperiment=$worstExp; reason=$worstReason"); $i++; continue }
    if ($line -match "^4\\)") { $out.Add("4) regressions=$regression; handling=$handling"); $i++; continue }
    if ($line -match "^5\\)") { $out.Add("5) nextHypothesis=$nextHyp; nextAction=$nextAct"); $i++; continue }
    $out.Add($line)
    $i++
  }

  ($out -join "`r`n") | Set-Content -Encoding UTF8 -Path $summaryPath

  $summaryJson = @{
    overallPass = $overallPass
    evidence = $evidence
    bestExperiment = $bestExp
    worstExperiment = $worstExp
    problems = @($problem | ForEach-Object { @{ name=$_.name; scenario=$_.scenario; level=$_.level; trigger=$_.trigger } })
  } | ConvertTo-Json -Depth 6
  $summaryJson | Set-Content -Encoding UTF8 -Path (Join-Path $artifactsRoot "report\\summary.json")
}

function Read-K6Summary([string]$jsonPath) {
  if (-not (Test-Path -LiteralPath $jsonPath)) { return $null }
  try {
    $raw = Get-Content -Raw -Encoding UTF8 -LiteralPath $jsonPath
    if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
    return ($raw | ConvertFrom-Json)
  } catch {
    return $null
  }
}

function Get-K6Metric([object]$summary, [string]$metric, [string]$field) {
  if ($null -eq $summary) { return $null }
  try {
    $m = $summary.metrics.$metric
    if ($null -eq $m) { return $null }
    return $m.values.$field
  } catch {
    return $null
  }
}

function Run-K6([string]$k6Exe, [string]$jsPath, [hashtable]$envMap, [string]$outTxt, [string]$outErr, [string]$summaryJson) {
  foreach ($k in $envMap.Keys) {
    Set-Item -Path ("Env:" + $k) -Value ([string]$envMap[$k])
  }

  $args = @(
    "run",
    $jsPath,
    "--summary-export", $summaryJson
  )

  $p = Start-Process -FilePath $k6Exe -ArgumentList $args -NoNewWindow -PassThru -Wait -RedirectStandardOutput $outTxt -RedirectStandardError $outErr
  return $p.ExitCode
}

function Run-Group([string]$outTxt, [string]$outErr, [int]$clients, [int]$senders, [int]$durationSeconds) {
  $args = @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", "scripts/ws-group-load-test/run.ps1",
    "-WsUrls", $WsUrls,
    "-HttpBase", $HttpBase,
    "-Clients", $clients,
    "-Senders", $senders,
    "-DurationSeconds", $durationSeconds,
    "-WarmupMs", 1500,
    "-MsgIntervalMs", $MsgIntervalMs,
    "-Password", "p",
    "-ReceiverSamplePct", 30,
    "-BodyBytes", 0
  )
  $p = Start-Process -FilePath "powershell" -ArgumentList $args -NoNewWindow -PassThru -Wait -RedirectStandardOutput $outTxt -RedirectStandardError $outErr
  return $p.ExitCode
}

New-Item -ItemType Directory -Force -Path $ArtifactsRoot | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $ArtifactsRoot "run") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $ArtifactsRoot "server") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $ArtifactsRoot "report") | Out-Null

Log "run-seq-12h start: ArtifactsRoot=$ArtifactsRoot"

Ensure-EnvVarFromUser "IM_MYSQL_USERNAME"
Ensure-EnvVarFromUser "IM_MYSQL_PASSWORD"

if ([string]::IsNullOrWhiteSpace($env:IM_AUTH_JWT_SECRET)) {
  $env:IM_AUTH_JWT_SECRET = "change-me-please-change-me-please-change-me"
}

$JavaExe = Resolve-JavaExe $JavaExe
$k6Exe = Resolve-K6Exe

Log "resolved: JavaExe=$JavaExe ; k6Exe=$k6Exe"

if (-not $SkipInfraCheck) {
  Assert-TcpOpen "127.0.0.1" 3306 "MySQL is required (see src/main/resources/application.yml)."
  Assert-TcpOpen "127.0.0.1" 6379 "Redis is required (see src/main/resources/application.yml)."
}

if (-not (Test-Path -LiteralPath $JarPath)) {
  if ($SkipBuild) { throw "Jar not found: $JarPath (and -SkipBuild set)" }
  mvn -q -DskipTests package
  if ($LASTEXITCODE -ne 0) { throw "mvn package failed (exit=$LASTEXITCODE)" }
  if (-not (Test-Path -LiteralPath $JarPath)) { throw "Jar not found after build: $JarPath" }
}

if ([string]::IsNullOrWhiteSpace($MysqlDatabase)) {
  $MysqlDatabase = ("mini_im_ptk_seq12h_{0}" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
}

$mysqlExe = Resolve-MySqlExe
Log "db: host=$MysqlHost port=$MysqlPort schema=$MysqlDatabase ; mysqlExe=$mysqlExe"
Ensure-Database -mysqlExe $mysqlExe -hostName $MysqlHost -port $MysqlPort -dbName $MysqlDatabase

Kill-JavaByServerPort $HttpPortA
Kill-JavaByServerPort $HttpPortB

Log "starting instances: httpA=$HttpPortA wsA=$WsPortA ; httpB=$HttpPortB wsB=$WsPortB"

$ts = Get-Date -Format "yyyyMMdd_HHmmss"
$srvOutA = Join-Path $ArtifactsRoot ("server\\gw-a_${ts}.out.log")
$srvErrA = Join-Path $ArtifactsRoot ("server\\gw-a_${ts}.err.log")
$srvOutB = Join-Path $ArtifactsRoot ("server\\gw-b_${ts}.out.log")
$srvErrB = Join-Path $ArtifactsRoot ("server\\gw-b_${ts}.err.log")

$argsA = @(
  "-Dfile.encoding=UTF-8",
  "-jar", $JarPath,
  "--server.port=$HttpPortA",
  "--spring.datasource.url=jdbc:mysql://${MysqlHost}:${MysqlPort}/${MysqlDatabase}?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
  "--im.gateway.ws.host=127.0.0.1",
  "--im.gateway.ws.port=$WsPortA",
  "--im.gateway.ws.instance-id=$InstanceIdA"
)
$argsB = @(
  "-Dfile.encoding=UTF-8",
  "-jar", $JarPath,
  "--server.port=$HttpPortB",
  "--spring.datasource.url=jdbc:mysql://${MysqlHost}:${MysqlPort}/${MysqlDatabase}?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
  "--im.gateway.ws.host=127.0.0.1",
  "--im.gateway.ws.port=$WsPortB",
  "--im.gateway.ws.instance-id=$InstanceIdB"
)

$pA = Start-Process -FilePath $JavaExe -ArgumentList $argsA -NoNewWindow -PassThru -WorkingDirectory (Get-Location).Path -RedirectStandardOutput $srvOutA -RedirectStandardError $srvErrA
$pB = Start-Process -FilePath $JavaExe -ArgumentList $argsB -NoNewWindow -PassThru -WorkingDirectory (Get-Location).Path -RedirectStandardOutput $srvOutB -RedirectStandardError $srvErrB

try {
  Wait-Port "127.0.0.1" $HttpPortA $StartupTimeoutMs
  Wait-Port "127.0.0.1" $HttpPortB $StartupTimeoutMs
  Wait-Port "127.0.0.1" $WsPortA $StartupTimeoutMs
  Wait-Port "127.0.0.1" $WsPortB $StartupTimeoutMs

  Log "instances ready: pA=$($pA.Id) pB=$($pB.Id)"

  Log "preflight: k6 ping (VUs=2, duration=10s)"
  $preDir = Join-Path $ArtifactsRoot "run"
  $preTxt = Join-Path $preDir ("preflight_k6_ping_${ts}.txt")
  $preErr = Join-Path $preDir ("preflight_k6_ping_${ts}.err.log")
  $preSum = Join-Path $preDir ("preflight_k6_ping_${ts}.summary.json")
  $preEnv = @{
    "WS_URLS" = $WsUrls
    "JWT_SECRET" = $env:IM_AUTH_JWT_SECRET
    "JWT_ISSUER" = "mini-im"
    "VUS" = "2"
    "DURATION" = "10s"
    "PING_INTERVAL_MS" = "1000"
    "WARMUP_MS" = "500"
    "USER_BASE" = "100000"
  }
  $preExit = Run-K6 -k6Exe $k6Exe -jsPath "scripts/k6/ws_ping.js" -envMap $preEnv -outTxt $preTxt -outErr $preErr -summaryJson $preSum
  $preSummary = Read-K6Summary $preSum
  $preConnectFail = Get-K6Metric $preSummary "ws_connect_fail" "count"
  $preAuthFail = Get-K6Metric $preSummary "ws_auth_fail" "count"
  $preServerError = Get-K6Metric $preSummary "ws_server_error" "count"
  if ($preExit -ne 0 -or ($preConnectFail -gt 0) -or ($preAuthFail -gt 0) -or ($preServerError -gt 0)) {
    throw "preflight failed: exit=$preExit connectFail=$preConnectFail authFail=$preAuthFail serverError=$preServerError"
  }

  $idx = Get-NextExperimentIndex $ArtifactsRoot
  $summaryPath = Join-Path $ArtifactsRoot "report\\summary.md"
  Ensure-SummaryTemplate $summaryPath

  $badStreak = @{}
  $disabled = @{}

  foreach ($level in $Levels) {
    foreach ($scenario in @("single_ping","single_e2e","group_chat")) {
      if ($disabled.ContainsKey($scenario) -and $disabled[$scenario]) {
        $expDir = New-Experiment $ArtifactsRoot $idx
        $expName = Split-Path -Leaf $expDir
        $repDir = Join-Path $expDir "report"
        $meta = @{
          name = $expName
          level = $level
          scenario = $scenario
          duration = $SegmentDuration
          startedAt = [DateTime]::UtcNow.ToString("o")
          finishedAt = [DateTime]::UtcNow.ToString("o")
          status = "skipped"
          note = "disabled due to consecutive problems"
          command = ""
          rawPath = ""
          logPath = ""
          trigger = "disabled"
          suspected = "consecutive problems"
          next = "skip remaining for this scenario"
        }
        ($meta | ConvertTo-Json -Depth 6) | Set-Content -Encoding UTF8 -Path (Join-Path $expDir "run.json")
        Write-ExperimentMd -path (Join-Path $repDir "experiment.md") -meta $meta
        Append-SummaryRow -path $summaryPath -meta $meta
        $idx += 1
        continue
      }

      Log ("segment start: E{0:d2} level={1} scenario={2} duration={3}" -f $idx, $level, $scenario, $SegmentDuration)
      $expDir = New-Experiment $ArtifactsRoot $idx
      $expName = Split-Path -Leaf $expDir
      $rawDir = Join-Path $expDir "raw"
      $logDir = Join-Path $expDir "logs"
      $repDir = Join-Path $expDir "report"

      $startedAt = [DateTime]::UtcNow.ToString("o")
      $status = "ok"
      $note = ""
      $trigger = ""
      $suspected = ""
      $next = ""

      if ($scenario -eq "single_ping") {
        $outTxt = Join-Path $rawDir ("k6_ping_vus${level}_${SegmentDuration}.txt")
        $outErr = Join-Path $logDir ("k6_ping_vus${level}_${SegmentDuration}.err.log")
        $sumJson = Join-Path $rawDir ("k6_ping_vus${level}_${SegmentDuration}.summary.json")

        $envMap = @{
          "WS_URLS" = $WsUrls
          "JWT_SECRET" = $env:IM_AUTH_JWT_SECRET
          "JWT_ISSUER" = "mini-im"
          "VUS" = "$level"
          "DURATION" = $SegmentDuration
          "PING_INTERVAL_MS" = "$PingIntervalMs"
          "WARMUP_MS" = "$WarmupMs"
          "USER_BASE" = "100000"
        }

        $cmd = "$k6Exe run scripts/k6/ws_ping.js --summary-export $sumJson"
        $exit = Run-K6 -k6Exe $k6Exe -jsPath "scripts/k6/ws_ping.js" -envMap $envMap -outTxt $outTxt -outErr $outErr -summaryJson $sumJson

        $sum = Read-K6Summary $sumJson
        $connectFail = Get-K6Metric $sum "ws_connect_fail" "count"
        $authFail = Get-K6Metric $sum "ws_auth_fail" "count"
        $serverError = Get-K6Metric $sum "ws_server_error" "count"
        $p99 = Get-K6Metric $sum "ws_pong_rtt_ms" "p(99)"

        if ($exit -ne 0 -or ($connectFail -gt 0) -or ($authFail -gt 0) -or ($serverError -gt 0)) {
          $status = "problem"
          $trigger = "k6 errors present or non-zero exit"
          $suspected = "WS connect/auth errors, server internal errors, or resource bottlenecks (timeout/disconnect)"
          $next = "Check server/*.err.log and this segment's k6 summary.json; consider lowering level or switching scenario"
        }

        $meta = @{
          name = $expName
          level = $level
          scenario = $scenario
          duration = $SegmentDuration
          startedAt = $startedAt
          finishedAt = [DateTime]::UtcNow.ToString("o")
          status = $status
          note = $note
          command = $cmd
          rawPath = $outTxt
          logPath = $outErr
          p99 = $p99
          connectFail = $connectFail
          authFail = $authFail
          serverError = $serverError
          trigger = $trigger
          suspected = $suspected
          next = $next
        }
        ($meta | ConvertTo-Json -Depth 6) | Set-Content -Encoding UTF8 -Path (Join-Path $expDir "run.json")
        Write-ExperimentMd -path (Join-Path $repDir "experiment.md") -meta $meta
        Append-SummaryRow -path $summaryPath -meta $meta
      }

      if ($scenario -eq "single_e2e") {
        $outTxt = Join-Path $rawDir ("k6_single_e2e_vus${level}_${SegmentDuration}.txt")
        $outErr = Join-Path $logDir ("k6_single_e2e_vus${level}_${SegmentDuration}.err.log")
        $sumJson = Join-Path $rawDir ("k6_single_e2e_vus${level}_${SegmentDuration}.summary.json")

        $envMap = @{
          "WS_URLS" = $WsUrls
          "JWT_SECRET" = $env:IM_AUTH_JWT_SECRET
          "JWT_ISSUER" = "mini-im"
          "USER_BASE" = "100000"
          "VUS" = "$level"
          "DURATION" = $SegmentDuration
          "MSG_INTERVAL_MS" = "$MsgIntervalMs"
          "WARMUP_MS" = "$WarmupMs"
          "ROLE_PINNED" = "1"
        }

        $cmd = "$k6Exe run scripts/k6/ws_single_e2e.js --summary-export $sumJson"
        $exit = Run-K6 -k6Exe $k6Exe -jsPath "scripts/k6/ws_single_e2e.js" -envMap $envMap -outTxt $outTxt -outErr $outErr -summaryJson $sumJson

        $sum = Read-K6Summary $sumJson
        $connectFail = Get-K6Metric $sum "ws_connect_fail" "count"
        $authFail = Get-K6Metric $sum "ws_auth_fail" "count"
        $serverError = Get-K6Metric $sum "ws_server_error" "count"
        $p99 = Get-K6Metric $sum "im_e2e_latency_ms" "p(99)"
        $sentRate = Get-K6Metric $sum "im_sent_total" "rate"
        $recvRate = Get-K6Metric $sum "im_recv_total" "rate"

        if ($exit -ne 0 -or ($connectFail -gt 0) -or ($authFail -gt 0) -or ($serverError -gt 0)) {
          $status = "problem"
          $trigger = "k6 errors present or non-zero exit"
          $suspected = "Tail latency due to server contention/GC/DB; possible auth/protocol mismatch"
          $next = "Check server logs around p99 spikes and GC/DB signals; if needed lower level and continue other scenarios"
        }

        $meta = @{
          name = $expName
          level = $level
          scenario = $scenario
          duration = $SegmentDuration
          startedAt = $startedAt
          finishedAt = [DateTime]::UtcNow.ToString("o")
          status = $status
          note = $note
          command = $cmd
          rawPath = $outTxt
          logPath = $outErr
          p99 = $p99
          connectFail = $connectFail
          authFail = $authFail
          serverError = $serverError
          sentPerSec = $sentRate
          recvPerSec = $recvRate
          trigger = $trigger
          suspected = $suspected
          next = $next
        }
        ($meta | ConvertTo-Json -Depth 6) | Set-Content -Encoding UTF8 -Path (Join-Path $expDir "run.json")
        Write-ExperimentMd -path (Join-Path $repDir "experiment.md") -meta $meta
        Append-SummaryRow -path $summaryPath -meta $meta
      }

      if ($scenario -eq "group_chat") {
        $outTxt = Join-Path $rawDir ("java_group_load_clients${level}_${GroupDurationSeconds}s.txt")
        $outErr = Join-Path $logDir ("java_group_load_clients${level}_${GroupDurationSeconds}s.err.log")
        $cmd = "powershell -File scripts/ws-group-load-test/run.ps1 -Clients $level -Senders 200 -DurationSeconds $GroupDurationSeconds"

        $exit = Run-Group -outTxt $outTxt -outErr $outErr -clients $level -senders 200 -durationSeconds $GroupDurationSeconds
        $groupJson = $null
        try {
          $raw = Get-Content -Raw -Encoding UTF8 -LiteralPath $outTxt
          if (-not [string]::IsNullOrWhiteSpace($raw)) { $groupJson = ($raw | ConvertFrom-Json) }
        } catch {
          $groupJson = $null
        }

        $connectFail = $null
        $authFail = $null
        $serverError = $null
        $p99 = $null
        $sentPerSec = $null
        $recvPerSec = $null

        if ($groupJson -ne $null) {
          try { $connectFail = $groupJson.ws.connect.fail } catch { }
          try { $authFail = $groupJson.auth.fail } catch { }
          try { $serverError = $groupJson.errors.wsError } catch { }
          try { $p99 = $groupJson.groupChat.e2eMs.p99 } catch { }
          try { $sentPerSec = $groupJson.groupChat.sentPerSec } catch { }
          try { $recvPerSec = $groupJson.groupChat.recv / [double]$GroupDurationSeconds } catch { }
        }

        if ($exit -ne 0 -or ($connectFail -gt 0) -or ($authFail -gt 0) -or ($serverError -gt 0)) {
          $status = "problem"
          $trigger = "java load errors present or non-zero exit"
          $suspected = "Group fanout/routing (Redis)/DB amplification; possible WS write queue backpressure"
          $next = "Check this segment out/err and server/*.err.log; if resource-bound lower Clients or switch to single-chat"
        }

        $meta = @{
          name = $expName
          level = $level
          scenario = $scenario
          duration = "${GroupDurationSeconds}s"
          startedAt = $startedAt
          finishedAt = [DateTime]::UtcNow.ToString("o")
          status = $status
          note = $note
          command = $cmd
          rawPath = $outTxt
          logPath = $outErr
          p99 = $p99
          connectFail = $connectFail
          authFail = $authFail
          serverError = $serverError
          sentPerSec = $sentPerSec
          recvPerSec = $recvPerSec
          trigger = $trigger
          suspected = $suspected
          next = $next
        }
        ($meta | ConvertTo-Json -Depth 6) | Set-Content -Encoding UTF8 -Path (Join-Path $expDir "run.json")
        Write-ExperimentMd -path (Join-Path $repDir "experiment.md") -meta $meta
        Append-SummaryRow -path $summaryPath -meta $meta
      }

      if (-not $badStreak.ContainsKey($scenario)) { $badStreak[$scenario] = 0 }
      if ($status -eq "problem") { $badStreak[$scenario] = [int]$badStreak[$scenario] + 1 } else { $badStreak[$scenario] = 0 }
      if ($badStreak[$scenario] -ge 2) {
        $disabled[$scenario] = $true
        Log "scenario disabled: $scenario (consecutive problems >=2)"
      }

      $idx += 1
    }
  }

  Finalize-Summary -artifactsRoot $ArtifactsRoot -summaryPath $summaryPath
} finally {
  if (-not $KeepProcesses) {
    try { Stop-Process -Id $pA.Id -Force -ErrorAction SilentlyContinue } catch { }
    try { Stop-Process -Id $pB.Id -Force -ErrorAction SilentlyContinue } catch { }
  }
}
