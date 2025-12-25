package com.miniim.gateway.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniim.auth.service.JwtService;
import com.miniim.domain.entity.ConversationEntity;
import com.miniim.domain.service.ConversationService;
import com.miniim.domain.service.MessageService;
import com.miniim.domain.service.SingleChatService;
import com.miniim.gateway.config.GatewayProperties;
import com.miniim.gateway.session.SessionRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class NettyWsServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NettyWsServer.class);

    private final GatewayProperties props;
    private final ObjectMapper objectMapper;
    private final JwtService jwtService;
    private final SessionRegistry sessionRegistry;
    private final MessageService messageService;
    private final ConversationService conversationService;
    private  final SingleChatService singleChatService;
    private final Executor imDbExecutor;
    private final ClientMsgIdIdempotency clientMsgIdIdempotency;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private Channel serverChannel;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public NettyWsServer(GatewayProperties props,
                         ObjectMapper objectMapper,
                         JwtService jwtService,
                         SessionRegistry sessionRegistry,
                         MessageService messageService,
                         ConversationService conversationService,
                         SingleChatService singleChatService,
                         @Qualifier("imDbExecutor") Executor imDbExecutor,
                         ClientMsgIdIdempotency clientMsgIdIdempotency) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.jwtService = jwtService;
        this.sessionRegistry = sessionRegistry;
        this.messageService = messageService;
        this.conversationService = conversationService;
        this.singleChatService = singleChatService;
        this.imDbExecutor = imDbExecutor;
        this.clientMsgIdIdempotency = clientMsgIdIdempotency;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        log.info("Starting Netty WS gateway on {}:{}{}", props.host(), props.port(), props.path());

        // boss: 负责 accept 新连接；worker: 负责处理已建立连接的读写事件
        // 这里是最常见的 Netty 线程模型。

        boss = new NioEventLoopGroup(1);
        worker = new NioEventLoopGroup();

        ServerBootstrap b = new ServerBootstrap();
        b.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();

                        // 1) HTTP 编解码：WebSocket 握手阶段是 HTTP 协议
                        p.addLast(new HttpServerCodec());

                        // 2) 聚合 HTTP 消息：把多个 HttpContent 聚合成 FullHttpRequest，便于握手处理
                        p.addLast(new HttpObjectAggregator(65536));
                        p.addLast(new LoggingHandler(LogLevel.INFO));

                        // 3) 空闲检测：60 秒内“没有读到任何数据”就触发 READER_IDLE
                        //    用途：清理死连接（比如手机切后台/断网，但 TCP 迟迟不触发 close）
                        p.addLast(new IdleStateHandler(0, 300, 0));

                        // 4) 握手鉴权：在 HTTP Upgrade 阶段校验 accessToken，并把 userId/exp 绑定到 channel
                        p.addLast(new WsHandshakeAuthHandler(props.path(), jwtService, sessionRegistry));

                        // 5) WebSocket 协议处理：
                        //    - 处理握手（upgrade）
                        //    - 自动处理 Ping/Pong 帧（注意：这是 WebSocket 协议层的 ping/pong）
                        //    - 把握手后的 TextWebSocketFrame 等上层帧往后传
                        p.addLast(new WebSocketServerProtocolHandler(props.path(), null, true));

                        // 6) 业务帧处理：我们自己定义的 JSON 文本协议（PING/...）
                        p.addLast(new WsFrameHandler(objectMapper, jwtService, sessionRegistry, messageService, conversationService, singleChatService, imDbExecutor, clientMsgIdIdempotency));
                    }
                }
                );

        try {
            serverChannel = b.bind(props.host(), props.port()).syncUninterruptibly().channel();
            log.info("Netty WS gateway started, listening on {}", serverChannel.localAddress());
        } catch (Exception e) {
            log.error("Failed to start Netty WS gateway on {}:{}{}", props.host(), props.port(), props.path(), e);
            stop();
            throw e;
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("Stopping Netty WS gateway...");
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (worker != null) {
            worker.shutdownGracefully();
        }
        if (boss != null) {
            boss.shutdownGracefully();
        }
        log.info("Netty WS gateway stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE; // 尽早启动
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }
}
