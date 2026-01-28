package com.miniim.common.cron;

import com.miniim.gateway.session.SessionRegistry;
import com.miniim.gateway.ws.WsResendService;
import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 可选兜底补发：基于“成员游标(last_delivered_msg_seq)”拉取未投递区间。
 *
 * <p>默认关闭，避免开发/调试阶段产生重复投递与日志噪声。</p>
 */
@ConditionalOnProperty(name = "im.cron.resend.enabled", havingValue = "true")
@Component
@RequiredArgsConstructor
@Slf4j
public class WsCron {

    private final SessionRegistry sessionRegistry;
    private final WsResendService wsResendService;

    @Scheduled(fixedDelayString = "${im.cron.resend.fixed-delay-ms:${im.cron.scan-dropped.fixed-delay-ms:30000}}")
    public void resendPendingMessagesForOnlineUsers() {
        List<Long> onlineUserIds = sessionRegistry.getOnlineUserIds();
        if (onlineUserIds.isEmpty()) {
            return;
        }
        for (Long userId : onlineUserIds) {
            if (userId == null || userId <= 0) {
                continue;
            }
            resendForUser(userId);
        }
    }

    private void resendForUser(long userId) {
        List<Channel> channels = sessionRegistry.getChannels(userId);
        if (channels.isEmpty()) {
            return;
        }
        wsResendService.resendForChannels(channels, userId, "cron");
    }
}
