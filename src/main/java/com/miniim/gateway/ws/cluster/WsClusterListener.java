package com.miniim.gateway.ws.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniim.gateway.session.SessionRegistry;
import com.miniim.gateway.ws.WsEnvelope;
import com.miniim.gateway.ws.WsWriter;
import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WsClusterListener implements MessageListener {

    private final ObjectMapper objectMapper;
    private final SessionRegistry sessionRegistry;
    private final WsWriter wsWriter;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (message == null || message.getBody() == null) {
            return;
        }
        String raw = new String(message.getBody(), StandardCharsets.UTF_8);
        WsClusterMessage msg;
        try {
            msg = objectMapper.readValue(raw, WsClusterMessage.class);
        } catch (Exception e) {
            log.debug("ws cluster message parse failed: {}", e.toString());
            return;
        }
        if (msg == null || msg.type() == null) {
            return;
        }
        if ("KICK".equalsIgnoreCase(msg.type())) {
            handleKick(msg);
            return;
        }
        if ("PUSH".equalsIgnoreCase(msg.type())) {
            handlePush(msg);
        }
    }

    private void handleKick(WsClusterMessage msg) {
        Long userId = msg.userId();
        if (userId == null || userId <= 0) {
            return;
        }
        String connId = msg.connId();
        if (connId != null && !connId.isBlank()) {
            for (Channel ch : sessionRegistry.getChannels(userId)) {
                if (ch == null || !ch.isActive()) {
                    continue;
                }
                String cid = ch.attr(SessionRegistry.ATTR_CONN_ID).get();
                if (!connId.equals(cid)) {
                    continue;
                }
                wsWriter.writeError(ch, "kicked", null, null);
                try {
                    ch.close();
                } catch (Exception e) {
                    log.debug("close channel failed: userId={}, err={}", userId, e.toString());
                }
            }
            return;
        }
        List<Channel> channels = sessionRegistry.getChannels(userId);
        for (Channel ch : channels) {
            if (ch == null || !ch.isActive()) {
                continue;
            }
            wsWriter.writeError(ch, "kicked", null, null);
            try {
                ch.close();
            } catch (Exception e) {
                log.debug("close channel failed: userId={}, err={}", userId, e.toString());
            }
        }
    }

    private void handlePush(WsClusterMessage msg) {
        WsEnvelope env = msg.envelope();
        if (env == null) {
            return;
        }

        Collection<Long> targets = collectTargets(msg);
        if (targets.isEmpty()) {
            return;
        }
        for (Long userId : targets) {
            if (userId == null || userId <= 0) {
                continue;
            }
            for (Channel ch : sessionRegistry.getChannels(userId)) {
                if (ch == null || !ch.isActive()) {
                    continue;
                }
                wsWriter.write(ch, env);
            }
        }
    }

    private static Collection<Long> collectTargets(WsClusterMessage msg) {
        WsEnvelope env = msg.envelope();
        if (env == null) {
            return List.of();
        }

        if (msg.userIds() != null && !msg.userIds().isEmpty()) {
            return msg.userIds();
        }
        if (msg.userId() != null) {
            List<Long> one = new ArrayList<>(1);
            one.add(msg.userId());
            return one;
        }
        return List.of();
    }
}
