package com.miniim.gateway.ws;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.miniim.domain.cache.GroupMemberIdsCache;
import com.miniim.domain.entity.GroupEntity;
import com.miniim.domain.entity.GroupMemberEntity;
import com.miniim.domain.entity.MessageEntity;
import com.miniim.domain.entity.SingleChatEntity;
import com.miniim.domain.enums.ChatType;
import com.miniim.domain.enums.MessageStatus;
import com.miniim.domain.mapper.GroupMemberMapper;
import com.miniim.domain.service.GroupService;
import com.miniim.domain.service.MessageService;
import com.miniim.domain.service.SingleChatService;
import com.miniim.gateway.session.SessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * WS 消息撤回：
 * - 仅发送者可撤回
 * - 仅允许在发送后 2 分钟内撤回
 * - 服务端保留原文，但对外输出统一展示“已撤回”
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WsMessageRevokeHandler {

    private static final int WINDOW_MINUTES = 2;

    private final SessionRegistry sessionRegistry;
    private final MessageService messageService;
    private final SingleChatService singleChatService;
    private final GroupService groupService;
    private final GroupMemberMapper groupMemberMapper;
    private final GroupMemberIdsCache groupMemberIdsCache;
    private final WsPushService wsPushService;
    private final WsWriter wsWriter;
    @Qualifier("imDbExecutor")
    private final Executor dbExecutor;

    public void handle(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
            wsWriter.writeError(ctx, "unauthorized", msg.getClientMsgId(), msg.getServerMsgId());
            return;
        }
        WsChannelSerialQueue.enqueue(channel, () -> handleSerial(ctx, msg, fromUserId));
    }

    private CompletionStage<Void> handleSerial(ChannelHandlerContext ctx, WsEnvelope msg, long fromUserId) {
        CompletableFuture<Void> done = new CompletableFuture<>();

        String targetServerMsgId = msg.getServerMsgId();
        if (targetServerMsgId == null || targetServerMsgId.isBlank()) {
            wsWriter.writeError(ctx, "missing_server_msg_id", msg.getClientMsgId(), null);
            done.complete(null);
            return done;
        }

        Long targetId = parsePositiveLongOrNull(targetServerMsgId);
        if (targetId == null) {
            wsWriter.writeError(ctx, "bad_server_msg_id", msg.getClientMsgId(), targetServerMsgId);
            done.complete(null);
            return done;
        }

        long ts = Instant.now().toEpochMilli();
        CompletableFuture<Outcome> future = CompletableFuture.supplyAsync(() -> revokeInDb(fromUserId, targetId), dbExecutor)
                .orTimeout(3, TimeUnit.SECONDS);

        future.whenComplete((out, err) -> {
            if (err != null) {
                log.error("revoke failed: fromUserId={}, targetId={}, cause={}", fromUserId, targetId, err.toString());
                ctx.executor().execute(() -> wsWriter.writeError(ctx, "internal_error", msg.getClientMsgId(), targetServerMsgId));
                done.complete(null);
                return;
            }
            if (out == null || out.reason != null) {
                String reason = out == null ? "internal_error" : out.reason;
                ctx.executor().execute(() -> wsWriter.writeError(ctx, reason, msg.getClientMsgId(), targetServerMsgId));
                done.complete(null);
                return;
            }

            WsEnvelope ev = new WsEnvelope();
            ev.type = "MESSAGE_REVOKED";
            ev.from = fromUserId;
            ev.serverMsgId = out.targetServerMsgId;
            ev.to = out.toUserId;
            ev.groupId = out.groupId;
            ev.ts = ts;

            ctx.executor().execute(() -> wsWriter.writeAck(ctx, fromUserId, msg.getClientMsgId(), out.targetServerMsgId, "revoked", null));

            // 单端优先，但仍尽量让“同用户多连接”保持一致：本地也推送给自己一次。
            wsPushService.pushToUserLocalOnly(fromUserId, ev);

            if (out.chatType == ChatType.SINGLE && out.toUserId != null && out.toUserId > 0) {
                wsPushService.pushToUser(out.toUserId, ev);
            }
            if (out.chatType == ChatType.GROUP && out.groupId != null && out.groupId > 0) {
                Set<Long> memberIds = loadGroupMemberIds(out.groupId);
                if (memberIds != null && !memberIds.isEmpty()) {
                    wsPushService.pushToUsers(memberIds, ev);
                }
            }

            done.complete(null);
        });

        return done;
    }

    private Outcome revokeInDb(long fromUserId, long targetId) {
        MessageEntity target = messageService.getById(targetId);
        if (target == null) {
            return Outcome.error("message_not_found");
        }
        if (target.getFromUserId() == null || target.getFromUserId() != fromUserId) {
            return Outcome.error("not_message_sender");
        }

        LocalDateTime createdAt = target.getCreatedAt();
        if (createdAt == null) {
            return Outcome.error("internal_error");
        }
        if (createdAt.isBefore(LocalDateTime.now().minusMinutes(WINDOW_MINUTES))) {
            return Outcome.error("revoke_timeout");
        }

        ChatType chatType = target.getChatType();
        if (chatType == null) {
            return Outcome.error("internal_error");
        }
        String targetServerMsgId = target.getServerMsgId();
        if (targetServerMsgId == null || targetServerMsgId.isBlank()) {
            targetServerMsgId = String.valueOf(targetId);
        }

        // 幂等：已撤回则直接视为成功
        if (target.getStatus() != MessageStatus.REVOKED) {
            messageService.update(new LambdaUpdateWrapper<MessageEntity>()
                    .eq(MessageEntity::getId, targetId)
                    .eq(MessageEntity::getFromUserId, fromUserId)
                    .ne(MessageEntity::getStatus, MessageStatus.REVOKED)
                    .set(MessageEntity::getStatus, MessageStatus.REVOKED)
                    .set(MessageEntity::getUpdatedAt, LocalDateTime.now()));
        }

        if (chatType == ChatType.SINGLE) {
            Long toUserId = target.getToUserId();
            if (toUserId == null || toUserId <= 0) {
                return Outcome.error("internal_error");
            }
            Long singleChatId = target.getSingleChatId();
            if (singleChatId != null && singleChatId > 0) {
                singleChatService.update(new LambdaUpdateWrapper<SingleChatEntity>()
                        .eq(SingleChatEntity::getId, singleChatId)
                        .set(SingleChatEntity::getUpdatedAt, LocalDateTime.now()));
            }
            return Outcome.single(targetServerMsgId, toUserId);
        }

        if (chatType == ChatType.GROUP) {
            Long groupId = target.getGroupId();
            if (groupId == null || groupId <= 0) {
                return Outcome.error("internal_error");
            }
            if (groupId != null && groupId > 0) {
                groupService.update(new LambdaUpdateWrapper<GroupEntity>()
                        .eq(GroupEntity::getId, groupId)
                        .set(GroupEntity::getUpdatedAt, LocalDateTime.now()));
            }
            return Outcome.group(targetServerMsgId, groupId);
        }

        return Outcome.error("internal_error");
    }

    private Set<Long> loadGroupMemberIds(long groupId) {
        Set<Long> cached = groupMemberIdsCache.get(groupId);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        List<GroupMemberEntity> members = groupMemberMapper.selectList(new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId));
        if (members == null || members.isEmpty()) {
            return Set.of();
        }
        Set<Long> out = new HashSet<>();
        for (GroupMemberEntity m : members) {
            if (m == null || m.getUserId() == null || m.getUserId() <= 0) {
                continue;
            }
            out.add(m.getUserId());
        }
        groupMemberIdsCache.put(groupId, out);
        return out;
    }

    private static Long parsePositiveLongOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            long v = Long.parseLong(s);
            return v > 0 ? v : null;
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    private static class Outcome {
        final ChatType chatType;
        final String targetServerMsgId;
        final Long toUserId;
        final Long groupId;
        final String reason;

        private Outcome(ChatType chatType, String targetServerMsgId, Long toUserId, Long groupId, String reason) {
            this.chatType = chatType;
            this.targetServerMsgId = targetServerMsgId;
            this.toUserId = toUserId;
            this.groupId = groupId;
            this.reason = reason;
        }

        static Outcome single(String serverMsgId, Long toUserId) {
            return new Outcome(ChatType.SINGLE, serverMsgId, toUserId, null, null);
        }

        static Outcome group(String serverMsgId, Long groupId) {
            return new Outcome(ChatType.GROUP, serverMsgId, null, groupId, null);
        }

        static Outcome error(String reason) {
            return new Outcome(null, null, null, null, reason);
        }
    }
}
