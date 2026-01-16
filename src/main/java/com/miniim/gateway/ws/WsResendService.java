package com.miniim.gateway.ws;

import com.miniim.domain.entity.MessageEntity;
import com.miniim.domain.mapper.MessageMapper;
import com.miniim.domain.mapper.MessageMentionMapper;
import com.miniim.domain.service.SingleChatMemberService;
import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * WS 离线/兜底补发：按“成员游标(last_delivered_msg_id)”拉取未投递区间并下发。
 *
 * <p>注意：写入必须切回 Netty eventLoop；DB 查询可在业务线程执行。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WsResendService {

    private static final int LIMIT = 200;
    private static final String RESEND_LOCK_PREFIX = "im:gw:lock:resend:";
    private static final long RESEND_LOCK_TTL_SECONDS = 10;

    private final MessageMapper messageMapper;
    private final MessageMentionMapper messageMentionMapper;
    private final SingleChatMemberService singleChatMemberService;
    private final WsWriter wsWriter;
    private final StringRedisTemplate redis;
    @Qualifier("imDbExecutor")
    private final Executor imDbExecutor;

    public void resendForChannelAsync(Channel target, long userId, String source) {
        if (target == null || !target.isActive() || userId <= 0) {
            return;
        }
        resendForChannelsAsync(List.of(target), userId, source);
    }

    public void resendForChannelsAsync(List<Channel> targets, long userId, String source) {
        if (targets == null || targets.isEmpty() || userId <= 0) {
            return;
        }
        if (!tryAcquireResendLock(userId)) {
            return;
        }
        CompletableFuture.runAsync(() -> resendForChannels(targets, userId, source), imDbExecutor)
                .orTimeout(3, TimeUnit.SECONDS)
                .exceptionally(e -> {
                    log.error("ws resend failed: source={}, userId={}, cause={}", source, userId, e.toString());
                    return null;
                });
    }

    public void resendForChannels(List<Channel> targets, long userId, String source) {
        if (targets == null || targets.isEmpty() || userId <= 0) {
            return;
        }

        singleChatMemberService.ensureMembersForUser(userId);

        List<MessageEntity> singleList = messageMapper.selectPendingSingleChatMessagesForUser(userId, LIMIT);
        List<MessageEntity> groupList = messageMapper.selectPendingGroupMessagesForUser(userId, LIMIT);
        if ((singleList == null || singleList.isEmpty()) && (groupList == null || groupList.isEmpty())) {
            return;
        }

        Set<Long> importantMsgIds = new HashSet<>();
        if (groupList != null && !groupList.isEmpty()) {
            List<Long> ids = groupList.stream().map(MessageEntity::getId).filter(x -> x != null && x > 0).toList();
            if (!ids.isEmpty()) {
                try {
                    List<Long> hits = messageMentionMapper.selectMentionedMessageIdsForUser(userId, ids);
                    if (hits != null) {
                        importantMsgIds.addAll(hits);
                    }
                } catch (Exception e) {
                    log.debug("ws resend mention check failed: source={}, userId={}, cause={}", source, userId, e.toString());
                }
            }
        }

        for (Channel target : targets) {
            if (target == null || !target.isActive()) {
                continue;
            }
            if (!target.isWritable()) {
                log.debug("ws resend skipped (channel unwritable): source={}, userId={}", source, userId);
                continue;
            }
            if (singleList != null) {
                for (MessageEntity msg : singleList) {
                    writePending(target, userId, msg, false, source);
                }
            }
            if (groupList != null) {
                for (MessageEntity msg : groupList) {
                    boolean important = msg != null && msg.getId() != null && importantMsgIds.contains(msg.getId());
                    writePending(target, userId, msg, important, source);
                }
            }
        }
    }

    private void writePending(Channel target, long userId, MessageEntity msgEntity, boolean important, String source) {
        if (target == null || msgEntity == null) {
            return;
        }
        WsEnvelope envelope = new WsEnvelope();
        envelope.setType(msgEntity.getChatType() == null ? null : msgEntity.getChatType().getDesc());
        envelope.setFrom(msgEntity.getFromUserId());
        envelope.setClientMsgId(msgEntity.getClientMsgId());
        envelope.setTo(userId);
        envelope.setGroupId(msgEntity.getGroupId());
        envelope.setServerMsgId(msgEntity.getServerMsgId());
        envelope.setBody(msgEntity.getStatus() == com.miniim.domain.enums.MessageStatus.REVOKED
                ? MessageEntity.REVOKED_PLACEHOLDER
                : msgEntity.getContent());
        envelope.setMsgType(msgEntity.getMsgType() == null ? null : msgEntity.getMsgType().getDesc());
        envelope.setTs(toEpochMilli(msgEntity.getCreatedAt()));
        if (important) {
            envelope.setImportant(true);
        }

        wsWriter.write(target, envelope).addListener(w -> {
            if (!w.isSuccess()) {
                log.warn(
                        "ws resend write failed: source={}, userId={}, serverMsgId={}, clientMsgId={}, groupId={}, cause={}",
                        source,
                        userId,
                        msgEntity.getServerMsgId(),
                        msgEntity.getClientMsgId(),
                        msgEntity.getGroupId(),
                        w.cause() == null ? "unknown" : w.cause().toString()
                );
            }
        });
    }

    private static long toEpochMilli(LocalDateTime time) {
        if (time == null) {
            return Instant.now().toEpochMilli();
        }
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private boolean tryAcquireResendLock(long userId) {
        // Redis 不可用时 fail-open：仍允许补发（单机模式/开发模式可用），但多实例下可能放大 DB 压力。
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                    RESEND_LOCK_PREFIX + userId,
                    String.valueOf(System.currentTimeMillis()),
                    RESEND_LOCK_TTL_SECONDS,
                    TimeUnit.SECONDS
            ));
        } catch (Exception e) {
            log.debug("ws resend lock failed: userId={}, err={}", userId, e.toString());
            return true;
        }
    }
}
