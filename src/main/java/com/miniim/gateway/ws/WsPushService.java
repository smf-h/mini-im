package com.miniim.gateway.ws;

import com.miniim.gateway.session.SessionRegistry;
import io.netty.channel.Channel;
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
    private final WsWriter wsWriter;

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
        wsWriter.write(ch, envelope).addListener(f -> {
            if (!f.isSuccess()) {
                log.debug("push failed: {}", f.cause() == null ? "unknown" : f.cause().toString());
            }
        });
    }
}
