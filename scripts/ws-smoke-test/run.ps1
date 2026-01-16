param(
  [string]$WsUrl = "ws://127.0.0.1:9001/ws",
  [string]$HttpBase = "http://127.0.0.1:8080",
  [string]$JwtSecret = "change-me-please-change-me-please-change-me",
  [long]$UserA = 10001,
  [long]$UserB = 10002,
  [int]$TimeoutMs = 8000,
  [ValidateSet("all","basic","idempotency","offline","cron","auth","friend_request")]
  [string]$Scenario = "all",
  [int]$AuthTokenTtlSeconds = 2,
  [switch]$CheckRedis,
  [string]$RedisHost = "127.0.0.1",
  [int]$RedisPort = 6379,
  [string]$RedisPassword = "",
  [switch]$CheckDb,
  [string]$DbHost = "127.0.0.1",
  [string]$DbName = "mini_im",
  [string]$DbUser = "root",
  [string]$DbPassword = ""
)

$ErrorActionPreference = 'Stop'

# Entry script for WS smoke tests.
# Note: keep this file ASCII-safe for Windows PowerShell 5.1 (UTF-8 no BOM can break parsing when it contains non-ASCII string literals).

# 1) Quick check: WS port reachable
$wsUri = [Uri]$WsUrl
$wsHost = $wsUri.Host
$wsPort = $wsUri.Port
if ($wsPort -lt 0) {
  throw "WsUrl must include a port, e.g. ws://127.0.0.1:9001/ws"
}
if (-not (Test-NetConnection -ComputerName $wsHost -Port $wsPort -WarningAction SilentlyContinue).TcpTestSucceeded) {
  throw "Cannot connect to $WsUrl (is the server running?)"
}

# 2) Compile + run Java test
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$javaFile = Join-Path $scriptDir "WsSmokeTest.java"
$outDir = Join-Path $scriptDir ".out"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Push-Location $scriptDir
try {
  javac -encoding UTF-8 -d "$outDir" "$javaFile"

  $json = java -cp "$outDir" `
    -Dws="$WsUrl" `
    -Dhttp="$HttpBase" `
    -DjwtSecret="$JwtSecret" `
    -DuserA="$UserA" `
    -DuserB="$UserB" `
    -DtimeoutMs="$TimeoutMs" `
    -Dscenario="$Scenario" `
    -DauthTokenTtlSeconds="$AuthTokenTtlSeconds" `
    -DredisHost="$RedisHost" `
    -DredisPort="$RedisPort" `
    -DredisPassword="$RedisPassword" `
    WsSmokeTest

  $result = $json | ConvertFrom-Json

  # 3) Optional: read redis route keys via redis-cli and attach to output JSON
  if ($CheckRedis) {
    if (-not (Get-Command redis-cli -ErrorAction SilentlyContinue)) {
      # Fallback: do not hard-fail when redis-cli is missing (common on Windows).
      # Keep the smoke test runnable; record that Redis inspection is skipped.
      $redis = [ordered]@{ skipped = $true; reason = "redis-cli not found in PATH" }
      $result | Add-Member -NotePropertyName "redis" -NotePropertyValue $redis -Force
      $CheckRedis = $false
    }

    if ($CheckRedis) {
      $redisArgs = @("-h", "$RedisHost", "-p", "$RedisPort")
      if (-not [string]::IsNullOrWhiteSpace($RedisPassword)) {
        $redisArgs += @("-a", "$RedisPassword")
      }

      $redis = [ordered]@{}
      foreach ($uid in @($UserA, $UserB)) {
        $key = "im:gw:route:$uid"
        $value = & redis-cli @redisArgs GET $key
        $ttl = & redis-cli @redisArgs TTL $key
        $redis["$key"] = [ordered]@{ value = $value; ttlSeconds = $ttl }
      }

      $result | Add-Member -NotePropertyName "redis" -NotePropertyValue $redis -Force
    }
  }

  # 3) Optional: query DB status via mysql CLI and attach to output JSON
  if ($CheckDb) {
    if (-not (Get-Command mysql -ErrorAction SilentlyContinue)) {
      # Fallback: keep smoke test runnable when mysql CLI is missing.
      $db = [ordered]@{ skipped = $true; reason = "mysql CLI not found in PATH" }
      $result | Add-Member -NotePropertyName "db" -NotePropertyValue $db -Force
      $CheckDb = $false
    }

    if ($CheckDb) {
      if ([string]::IsNullOrWhiteSpace($DbPassword)) {
        if (-not [string]::IsNullOrWhiteSpace($env:MYSQL_PWD)) {
          $DbPassword = $env:MYSQL_PWD
        }
      }
    }

    if ([string]::IsNullOrWhiteSpace($DbPassword)) {
      throw "CheckDb needs -DbPassword or env MYSQL_PWD"
    }

    $env:MYSQL_PWD = $DbPassword

    if ($null -ne $result.scenarios) {
      foreach ($prop in $result.scenarios.PSObject.Properties) {
        $scenarioObj = $prop.Value
        if ($null -eq $scenarioObj) { continue }
        if ($null -eq $scenarioObj.serverMsgId) { continue }

        $serverMsgId = [string]$scenarioObj.serverMsgId
        $dbTable = $scenarioObj.dbTable
        if ($dbTable -eq "t_friend_request") {
          if ($serverMsgId -notmatch '^[0-9]+$') {
            throw "unexpected friend_request serverMsgId (not digits): $serverMsgId"
          }
          $status = mysql -u "$DbUser" -h "$DbHost" -N -s -D "$DbName" -e "SELECT status FROM t_friend_request WHERE id=${serverMsgId} LIMIT 1;"
        } else {
          $status = mysql -u "$DbUser" -h "$DbHost" -N -s -D "$DbName" -e "SELECT status FROM t_message WHERE server_msg_id='${serverMsgId}' LIMIT 1;"
        }
        $scenarioObj | Add-Member -NotePropertyName "dbStatus" -NotePropertyValue ($status.Trim()) -Force
      }
    }
  }

  $result | ConvertTo-Json -Depth 10
} finally {
  Pop-Location
}
