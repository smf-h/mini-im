package com.miniim.gateway.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniim.gateway.session.SessionRegistry;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WsPushService {

    private final SessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;

    public void pushToUser(long userId, WsEnvelope envelope) {
        List<Channel> channels = sessionRegistry.getChannels(userId);
        if (channels == null || channels.isEmpty()) {
            return;
        }
        for (Channel ch : channels) {
            pushToChannel(ch, envelope);
        }
    }

    public void pushToUsers(Collection<Long> userIds, WsEnvelope envelope) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        for (Long uid : userIds) {
            if (uid == null || uid <= 0) {
                continue;
            }
            pushToUser(uid, envelope);
        }
    }

    private void pushToChannel(Channel ch, WsEnvelope envelope) {
        if (ch == null || !ch.isActive()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(envelope);
            ch.eventLoop().execute(() -> {
                try {
                    ch.writeAndFlush(new TextWebSocketFrame(json));
                } catch (Exception e) {
                    log.debug("push failed: {}", e.toString());
                }
            });
        } catch (Exception e) {
            log.debug("push serialize failed: {}", e.toString());
        }
    }
}

