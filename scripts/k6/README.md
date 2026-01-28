# k6 分布式压测（Windows / PowerShell）

> 说明：k6 开源版本身是“单机单进程”。所谓“分布式”就是在多台压测机上同时运行同一份脚本，并把结果输出到同一个时序库（推荐）或各自导出后汇总对比。

## 0. 前置：后端 WS 协议要点

- WS：默认 `ws://127.0.0.1:9001/ws`
- 握手鉴权：支持 `Authorization: Bearer <token>` 或 query 参数 `token/accessToken`
- 连接后发送一帧 `{"type":"AUTH","token":"..."}`，服务端回 `AUTH_OK`

## 1. 安装 k6（每台压测机）

任选其一：

- Chocolatey：`choco install k6 -y`
- Winget：`winget install k6.k6`
- 手动：下载 k6 Windows release，解压后将 `k6.exe` 加入 PATH

验证：`k6 version`

## 2. 单机快速跑通（建议先做）

PING/PONG RTT：

```powershell
.\scripts\k6\run-ws-ping.ps1 `
  -WsUrls "ws://127.0.0.1:9001/ws" `
  -JwtSecret "change-me-please-change-me-please-change-me" `
  -Vus 200 -Duration "2m"
```

单聊 E2E（同一台压测机内偶数 VU 发，奇数 VU 收）：

```powershell
.\scripts\k6\run-ws-single-e2e.ps1 `
  -WsUrls "ws://127.0.0.1:9001/ws;ws://127.0.0.1:9002/ws" `
  -JwtSecret "change-me-please-change-me-please-change-me" `
  -Vus 400 -Duration "2m" -MsgIntervalMs 50 -RolePinned
```

## 3. 分布式执行（多台压测机并发）

关键原则：每台压测机必须使用不同的 `UserBase`，避免多个压测机用到相同 userId。

示例：两台压测机 LoadGen-A / LoadGen-B

LoadGen-A：

```powershell
.\scripts\k6\run-ws-single-e2e.ps1 `
  -WsUrls "ws://GATEWAY-A:9001/ws;ws://GATEWAY-B:9002/ws" `
  -JwtSecret "你的IM_AUTH_JWT_SECRET" `
  -UserBase 100000 -Vus 2000 -Duration "10m" -MsgIntervalMs 100 -RolePinned
```

LoadGen-B：

```powershell
.\scripts\k6\run-ws-single-e2e.ps1 `
  -WsUrls "ws://GATEWAY-A:9001/ws;ws://GATEWAY-B:9002/ws" `
  -JwtSecret "你的IM_AUTH_JWT_SECRET" `
  -UserBase 200000 -Vus 2000 -Duration "10m" -MsgIntervalMs 100 -RolePinned
```

说明：
- `-RolePinned`：偶数 VU 固定连第一个 WS 地址（发送侧），奇数 VU 固定连第二个 WS 地址（接收侧），更容易构造“跨网关实例”的消息路径。
- 不加 `-RolePinned`：会随机挑 WS 地址，更接近 LB。

## 4. 输出与解读（最小可用）

直接看 k6 终端 summary：
- `ws_connect_ok/ws_connect_fail`：连接成功/失败
- `ws_auth_ok/ws_auth_fail`：鉴权成功/失败（以收到 `AUTH_OK` 为准）
- `ws_pong_rtt_ms`：PING/PONG RTT 分位数（即时性基线）
- `im_sent_total/im_recv_unique_total/im_dup_total`：发送/接收去重/重复
- `im_e2e_latency_ms`：单聊 E2E 延迟分位数
- `im_reorder_total`：接收端检测到的乱序次数（基于发送端自增 seq）

如需多压测机统一看板：建议把每台压测机输出到同一个 InfluxDB/Grafana（需要你提供你希望用 Docker 还是本机安装）。

