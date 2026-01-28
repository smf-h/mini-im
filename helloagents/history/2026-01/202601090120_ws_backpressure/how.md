# how - 实现方案（最小改动）

## 变更点

1) Netty 出站写缓冲水位

- 在 `NettyWsServer` 的 `ServerBootstrap.childOption` 增加 `ChannelOption.WRITE_BUFFER_WATER_MARK`（默认低/高水位 256KB/512KB）。
- 目的：让 Netty 的 `channel.isWritable()` 与 `channelWritabilityChanged` 事件在“慢端积压”时生效。

2) 写出降级（拒绝继续堆积）

- 在 `WsWriter` 写出前判断 `channel.isWritable()`：
  - 连接 unwritable 时直接返回 failed future（默认开启），避免继续堆积出站缓冲。

3) 慢端踢出（延迟关闭，避免瞬时误杀）

- 增加 `WsBackpressureHandler`：
  - 监听 `channelWritabilityChanged`
  - 连接进入 unwritable 时记录起始时间，并在阈值到达后若仍 unwritable 则关闭连接
  - 输出 warn 日志：`ws backpressure: closing slow consumer channel`，包含 `uid/cid` 与 `bytesBeforeUnwritable/bytesBeforeWritable`

## 配置项

前缀：`im.gateway.ws.backpressure`

- `enabled`：是否启用（默认 true）
- `write-buffer-low-water-mark-bytes`：低水位（默认 262144）
- `write-buffer-high-water-mark-bytes`：高水位（默认 524288）
- `close-unwritable-after-ms`：持续 unwritable 后踢出（默认 3000；设置 `<0` 禁用踢出）
- `drop-when-unwritable`：连接 unwritable 时拒绝继续写入（默认 true）

