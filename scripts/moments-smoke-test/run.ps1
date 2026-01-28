param(
  [string]$HttpBaseA = "http://127.0.0.1:8080",
  [string]$HttpBaseB = "http://127.0.0.1:8082",
  [string]$Password = "p",
  [int]$TimeoutMs = 20000
)

$ErrorActionPreference = 'Stop'

function Strip-Slash([string]$s) {
  if ($null -eq $s) { return "" }
  return $s.TrimEnd('/')
}

function Mask-Token([string]$token) {
  if ([string]::IsNullOrWhiteSpace($token)) { return $token }
  if ($token.Length -le 10) { return "***" }
  return $token.Substring(0, 6) + "..." + $token.Substring($token.Length - 4)
}

function Ensure-Ok($resp, [string]$hint) {
  if ($null -eq $resp) { throw "empty_response: $hint" }
  if (-not $resp.ok) {
    $code = $resp.code
    $msg = $resp.message
    throw "api_not_ok: $hint (code=$code, message=$msg)"
  }
  return $resp.data
}

function Invoke-Get([string]$base, [string]$path, [string]$token) {
  $headers = @{}
  if (-not [string]::IsNullOrWhiteSpace($token)) {
    $headers["Authorization"] = "Bearer $token"
  }
  return Invoke-RestMethod -Method Get -Uri ($base + $path) -Headers $headers -TimeoutSec ([Math]::Ceiling($TimeoutMs / 1000.0))
}

function Invoke-Post([string]$base, [string]$path, [string]$token, $body) {
  $headers = @{}
  if (-not [string]::IsNullOrWhiteSpace($token)) {
    $headers["Authorization"] = "Bearer $token"
  }
  $jsonBody = $null
  if ($null -ne $body) {
    $jsonBody = ($body | ConvertTo-Json -Depth 10 -Compress)
  }
  return Invoke-RestMethod -Method Post -Uri ($base + $path) -Headers $headers -ContentType "application/json" -Body $jsonBody -TimeoutSec ([Math]::Ceiling($TimeoutMs / 1000.0))
}

function Login([string]$base, [string]$username, [string]$password) {
  $resp = Invoke-Post $base "/auth/login" "" @{ username = $username; password = $password }
  $data = Ensure-Ok $resp "auth/login"
  return [pscustomobject]@{
    userId = [string]$data.userId
    accessToken = [string]$data.accessToken
    refreshToken = [string]$data.refreshToken
  }
}

$HttpBaseA = Strip-Slash $HttpBaseA
$HttpBaseB = Strip-Slash $HttpBaseB

$rand = ([Guid]::NewGuid().ToString("N")).Substring(0, 6)
$userA = "mom_a_$rand"
$userB = "mom_b_$rand"

$out = [ordered]@{
  ok = $false
  vars = [ordered]@{
    httpA = $HttpBaseA
    httpB = $HttpBaseB
    userAName = $userA
    userBName = $userB
    timeoutMs = $TimeoutMs
  }
  steps = @()
}

try {
  $a = Login $HttpBaseA $userA $Password
  $b = Login $HttpBaseB $userB $Password
  $out.steps += @{ dir = "A->HTTP"; name = "login"; raw = @{ userId = $a.userId; accessToken = (Mask-Token $a.accessToken) } }
  $out.steps += @{ dir = "B->HTTP"; name = "login"; raw = @{ userId = $b.userId; accessToken = (Mask-Token $b.accessToken) } }

  $bProfile = Ensure-Ok (Invoke-Get $HttpBaseA ("/user/profile?userId=" + $b.userId) $a.accessToken) "user/profile(B)"
  $bFriendCode = [string]$bProfile.friendCode
  if ([string]::IsNullOrWhiteSpace($bFriendCode)) { throw "missing_friend_code(B)" }
  $out.steps += @{ dir = "A->HTTP"; name = "user/profile(B)"; raw = @{ friendCode = $bFriendCode } }

  $fr = Ensure-Ok (Invoke-Post $HttpBaseA "/friend/request/by-code" $a.accessToken @{ toFriendCode = $bFriendCode; message = "hi-$rand" }) "friend/request/by-code"
  $requestId = [string]$fr.requestId
  if ([string]::IsNullOrWhiteSpace($requestId)) { throw "missing_request_id" }
  $out.steps += @{ dir = "A->HTTP"; name = "friend/request/by-code"; raw = @{ requestId = $requestId } }

  $decide = Ensure-Ok (Invoke-Post $HttpBaseB "/friend/request/decide" $b.accessToken @{ requestId = [long]$requestId; action = "accept" }) "friend/request/decide"
  $out.steps += @{ dir = "B->HTTP"; name = "friend/request/decide"; raw = @{ ok = $true; singleChatId = $decide.singleChatId } }

  $content = "moment-$rand"
  $post = Ensure-Ok (Invoke-Post $HttpBaseA "/moment/post/create" $a.accessToken @{ content = $content }) "moment/post/create"
  $postId = [string]$post.postId
  if ([string]::IsNullOrWhiteSpace($postId)) { throw "missing_post_id" }
  $out.steps += @{ dir = "A->HTTP"; name = "moment/post/create"; raw = @{ postId = $postId } }

  $feedB = Ensure-Ok (Invoke-Get $HttpBaseB "/moment/feed/cursor?limit=20" $b.accessToken) "moment/feed/cursor(B)"
  $found = $false
  foreach ($p in $feedB) {
    if ([string]$p.id -eq $postId) {
      if ([string]$p.content -ne $content) { throw "feed_content_mismatch" }
      $found = $true
      break
    }
  }
  if (-not $found) { throw "feed_missing_post_for_friend" }
  $out.steps += @{ dir = "B->HTTP"; name = "moment/feed/cursor"; raw = @{ foundPostId = $postId } }

  $like1 = Ensure-Ok (Invoke-Post $HttpBaseB "/moment/like/toggle" $b.accessToken @{ postId = [long]$postId }) "moment/like/toggle#1"
  if (-not $like1.liked) { throw "expected_liked_true" }
  $out.steps += @{ dir = "B->HTTP"; name = "moment/like/toggle#1"; raw = @{ liked = $like1.liked; likeCount = $like1.likeCount } }

  $like2 = Ensure-Ok (Invoke-Post $HttpBaseB "/moment/like/toggle" $b.accessToken @{ postId = [long]$postId }) "moment/like/toggle#2"
  if ($like2.liked) { throw "expected_liked_false" }
  $out.steps += @{ dir = "B->HTTP"; name = "moment/like/toggle#2"; raw = @{ liked = $like2.liked; likeCount = $like2.likeCount } }

  $cResp = Ensure-Ok (Invoke-Post $HttpBaseB "/moment/comment/create" $b.accessToken @{ postId = [long]$postId; content = "c-$rand" }) "moment/comment/create"
  $commentId = [string]$cResp.commentId
  if ([string]::IsNullOrWhiteSpace($commentId)) { throw "missing_comment_id" }
  $out.steps += @{ dir = "B->HTTP"; name = "moment/comment/create"; raw = @{ commentId = $commentId } }

  $comments = Ensure-Ok (Invoke-Get $HttpBaseB ("/moment/comment/cursor?postId=$postId&limit=20") $b.accessToken) "moment/comment/cursor"
  $cFound = $false
  foreach ($c in $comments) {
    if ([string]$c.id -eq $commentId) { $cFound = $true; break }
  }
  if (-not $cFound) { throw "comment_missing" }
  $out.steps += @{ dir = "B->HTTP"; name = "moment/comment/cursor"; raw = @{ foundCommentId = $commentId } }

  Ensure-Ok (Invoke-Post $HttpBaseB "/moment/comment/delete" $b.accessToken @{ commentId = [long]$commentId }) "moment/comment/delete"
  $out.steps += @{ dir = "B->HTTP"; name = "moment/comment/delete"; raw = @{ commentId = $commentId } }

  Ensure-Ok (Invoke-Post $HttpBaseA "/moment/post/delete" $a.accessToken @{ postId = [long]$postId }) "moment/post/delete"
  $out.steps += @{ dir = "A->HTTP"; name = "moment/post/delete"; raw = @{ postId = $postId } }

  $feedB2 = Ensure-Ok (Invoke-Get $HttpBaseB "/moment/feed/cursor?limit=20" $b.accessToken) "moment/feed/cursor(B)#after_delete"
  foreach ($p in $feedB2) {
    if ([string]$p.id -eq $postId) { throw "feed_still_contains_deleted_post" }
  }

  $out.ok = $true
} catch {
  $out.error = [string]$($_.Exception.Message)
  $out.ok = $false
  throw
} finally {
  $outJson = ($out | ConvertTo-Json -Depth 20 -Compress)
  Write-Output $outJson
}

