param(
  [string]$WsUrl = "ws://127.0.0.1:9001/ws",
  [string]$JwtSecret = "change-me-please-change-me-please-change-me",
  [long]$UserA = 10001,
  [long]$UserB = 10002,
  [int]$TimeoutMs = 8000,
  [switch]$CheckDb,
  [string]$DbHost = "127.0.0.1",
  [string]$DbName = "mini_im",
  [string]$DbUser = "root",
  [string]$DbPassword = ""
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$javaFile = Join-Path $scriptDir "WsSmokeTest.java"
$outDir = Join-Path $scriptDir ".out"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Push-Location $scriptDir
try {
  javac -d "$outDir" "$javaFile"
  $json = java -cp "$outDir" `
    -Dws="$WsUrl" `
    -DjwtSecret="$JwtSecret" `
    -DuserA="$UserA" `
    -DuserB="$UserB" `
    -DtimeoutMs="$TimeoutMs" `
    WsSmokeTest

  $result = $json | ConvertFrom-Json

  if ($CheckDb) {
    if ([string]::IsNullOrWhiteSpace($DbPassword)) {
      if (-not [string]::IsNullOrWhiteSpace($env:MYSQL_PWD)) {
        $DbPassword = $env:MYSQL_PWD
      }
    }

    if ([string]::IsNullOrWhiteSpace($DbPassword)) {
      throw "CheckDb 需要提供 -DbPassword 或设置环境变量 MYSQL_PWD"
    }

    $env:MYSQL_PWD = $DbPassword
    $serverMsgId = [string]$result.serverMsgId
    $status = mysql -u "$DbUser" -h "$DbHost" -N -s -D "$DbName" -e "SELECT status FROM t_message WHERE server_msg_id='${serverMsgId}' LIMIT 1;"
    $result | Add-Member -NotePropertyName "dbStatus" -NotePropertyValue ($status.Trim()) -Force
  }

  $result | ConvertTo-Json -Depth 5
} finally {
  Pop-Location
}
