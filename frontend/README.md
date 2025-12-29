# mini-im 前端（Vue3 + TypeScript + Vite）

## 启动

前置：后端已启动（HTTP `:8080` + WS `:9001/ws`），并且本地 MySQL/Redis 可用。

```bash
cd frontend
npm install
npm run dev
```

浏览器打开 Vite 输出的地址（默认 `http://127.0.0.1:5173`）。

## 环境变量（可选）
- `VITE_HTTP_BASE`：默认 `http://127.0.0.1:8080`
- `VITE_WS_URL`：默认 `ws://127.0.0.1:9001/ws`

## 鉴权说明
- HTTP：`Authorization: Bearer <accessToken>`
- WS：浏览器端握手使用 `?token=<accessToken>`（浏览器限制无法设置握手 Authorization header），连接建立后再发送 `AUTH` 帧。
