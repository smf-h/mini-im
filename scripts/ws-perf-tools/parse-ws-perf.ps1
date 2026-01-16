param(
  [Parameter(Mandatory = $true)]
  [string]$LogPath,
  [int]$MaxLines = 200000
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function New-List() {
  # Return as scalar; otherwise PowerShell enumerates empty lists as "no output" => $null.
  return ,([System.Collections.Generic.List[long]]::new())
}

function Add-Val([hashtable]$map, [string]$key, [long]$val) {
  if ($null -eq $map) { return }
  if (-not $map.ContainsKey($key)) { $map[$key] = (New-List) }
  if ($null -eq $map[$key]) { $map[$key] = (New-List) }
  $map[$key].Add($val) | Out-Null
}

function Percentiles([System.Collections.Generic.List[long]]$list) {
  if ($null -eq $list -or $list.Count -eq 0) { return @{ p50 = $null; p95 = $null; p99 = $null; count = 0 } }
  $arr = $list.ToArray()
  [Array]::Sort($arr)
  function P([int]$p) {
    $idx = [int][Math]::Round(($p / 100.0) * ($arr.Length - 1))
    $idx = [Math]::Max(0, [Math]::Min($arr.Length - 1, $idx))
    return $arr[$idx]
  }
  return @{ p50 = (P 50); p95 = (P 95); p99 = (P 99); count = $arr.Length }
}

if (-not (Test-Path -LiteralPath $LogPath)) { throw "Log not found: $LogPath" }

$byType = @{}
$lineCount = 0

foreach ($line in (Get-Content -LiteralPath $LogPath -Encoding UTF8)) {
  if ($lineCount -ge $MaxLines) { break }
  $lineCount++

  if ($null -eq $line) { continue }
  if ($line -notmatch "ws_perf\s+(\w+)") { continue }

  $t = $Matches[1]
  if (-not $byType.ContainsKey($t)) { $byType[$t] = @{} }
  $map = $byType[$t]

  # totalMs=123
  $m = [regex]::Match($line, "totalMs=(\d+)")
  if ($m.Success) { Add-Val $map "totalMs" ([long]$m.Groups[1].Value) }

  # ...Ms=123 (queueMs/dbQueueMs/.../redisPubMs)
  foreach ($mm in [regex]::Matches($line, "(\w+Ms)=(\d+)")) {
    $k = $mm.Groups[1].Value
    $v = [long]$mm.Groups[2].Value
    Add-Val $map $k $v
  }
}

$out = [ordered]@{
  ok = $true
  logPath = $LogPath
  maxLines = $MaxLines
  types = [ordered]@{}
}

foreach ($typeKey in ($byType.Keys | Sort-Object)) {
  $fields = $byType[$typeKey]
  $typed = [ordered]@{}
  foreach ($fieldKey in ($fields.Keys | Sort-Object)) {
    $typed[$fieldKey] = Percentiles $fields[$fieldKey]
  }
  $out.types[$typeKey] = $typed
}

$out | ConvertTo-Json -Depth 8
