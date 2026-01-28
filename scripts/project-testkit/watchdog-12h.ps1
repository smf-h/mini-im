param(
  [string]$ArtifactsRoot = "artifacts/project-testkit/20260125_213233",
  [int]$Hours = 12,
  [int]$CheckIntervalSeconds = 30
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class WatchdogPower {
  [DllImport("kernel32.dll")]
  public static extern uint SetThreadExecutionState(uint esFlags);
}
"@

$ES_CONTINUOUS = 0x80000000
$ES_SYSTEM_REQUIRED = 0x00000001
$ES_AWAYMODE_REQUIRED = 0x00000040

function Log([string]$msg) {
  $ts = [DateTime]::UtcNow.ToString("o")
  $line = "[${ts}] ${msg}"
  $line | Add-Content -Encoding UTF8 -Path $watchdogLog
}

function Find-Runners() {
  $procs = Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" -ErrorAction SilentlyContinue
  if ($null -eq $procs) { return @() }
  $hits = @($procs | Where-Object { $_.CommandLine -match "run-seq-12h\.ps1" })
  return $hits
}

function Start-Runner() {
  $ts = Get-Date -Format "yyyyMMdd_HHmmss"
  $log = Join-Path $ArtifactsRoot ("run\\seq_12h_${ts}.log")
  $err = Join-Path $ArtifactsRoot ("run\\seq_12h_${ts}.err.log")
  $args = @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", "scripts/project-testkit/run-seq-12h.ps1",
    "-ArtifactsRoot", $ArtifactsRoot
  )
  $p = Start-Process -FilePath "powershell" -ArgumentList $args -NoNewWindow -PassThru -RedirectStandardOutput $log -RedirectStandardError $err
  Log "runner started pid=$($p.Id) log=$log err=$err"
  return $p.Id
}

New-Item -ItemType Directory -Force -Path (Join-Path $ArtifactsRoot "run") | Out-Null
$watchdogLog = Join-Path $ArtifactsRoot "run\\watchdog_12h.log"
$deadline = [DateTime]::UtcNow.AddHours($Hours)

Log "watchdog start: hours=$Hours interval=$CheckIntervalSeconds artifacts=$ArtifactsRoot"

while ([DateTime]::UtcNow -lt $deadline) {
  try {
    # Keep system awake while watchdog is running (no permanent power setting changes).
    [void][WatchdogPower]::SetThreadExecutionState($ES_CONTINUOUS -bor $ES_SYSTEM_REQUIRED -bor $ES_AWAYMODE_REQUIRED)

    $runners = @(Find-Runners)
    if ($runners.Count -eq 0) {
      Start-Runner | Out-Null
    } elseif ($runners.Count -gt 1) {
      # Keep the newest runner, stop others (avoid duplicate runs).
      $items = @()
      foreach ($r in $runners) {
        $pid = [int]$r.ProcessId
        $start = $null
        try { $start = (Get-Process -Id $pid -ErrorAction Stop).StartTime } catch { }
        $items += [pscustomobject]@{ pid=$pid; start=$start; cmd=$r.CommandLine }
      }
      $keep = $items | Sort-Object start -Descending | Select-Object -First 1
      foreach ($it in $items) {
        if ($it.pid -eq $keep.pid) { continue }
        try {
          Stop-Process -Id $it.pid -Force -ErrorAction SilentlyContinue
          Log "stopped duplicate runner pid=$($it.pid) keep=$($keep.pid)"
        } catch { }
      }
    } else {
      Log "runner ok pid=$($runners[0].ProcessId)"
    }
  } catch {
    Log ("watchdog error: " + $_.Exception.Message)
  }

  Start-Sleep -Seconds $CheckIntervalSeconds
}

[void][WatchdogPower]::SetThreadExecutionState($ES_CONTINUOUS)
Log "watchdog finished"
