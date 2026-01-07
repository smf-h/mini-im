package com.miniim.gateway.ws;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.miniim.common.content.ForbiddenWordFilter;
import com.miniim.domain.cache.GroupMemberIdsCache;
import com.miniim.domain.entity.GroupMemberEntity;
import com.miniim.domain.entity.MessageEntity;
import com.miniim.domain.entity.MessageMentionEntity;
import com.miniim.domain.enums.AckType;
import com.miniim.domain.enums.ChatType;
import com.miniim.domain.enums.MessageStatus;
import com.miniim.domain.enums.MessageType;
import com.miniim.domain.enums.MentionType;
import com.miniim.domain.mapper.GroupMemberMapper;
import com.miniim.domain.mapper.MessageMentionMapper;
import com.miniim.domain.service.GroupService;
import com.miniim.domain.service.MessageService;
import com.miniim.gateway.session.SessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class WsGroupChatHandler {

    private static final int MAX_BODY_LEN = 4096;

    private final SessionRegistry sessionRegistry;
    private final ClientMsgIdIdempotency idempotency;
    private final MessageService messageService;
    private final GroupMemberMapper groupMemberMapper;
    private final GroupMemberIdsCache groupMemberIdsCache;
    private final MessageMentionMapper messageMentionMapper;
    private final GroupService groupService;
    private final ForbiddenWordFilter forbiddenWordFilter;
    private final WsPushService wsPushService;
    private final GroupChatDispatchService groupChatDispatchService;
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

        if (!validate(ctx, msg)) {
            done.complete(null);
            return done;
        }

        final long ts = Instant.now().toEpochMilli();
        final Long groupId = msg.getGroupId();
        final Long messageId = IdWorker.getId();
        final String serverMsgId = String.valueOf(messageId);
        final String clientMsgId = msg.getClientMsgId();
        final String idempotencyKey = idempotency.key(fromUserId, "GROUP_CHAT:" + groupId, clientMsgId);
        final String sanitizedBody = forbiddenWordFilter.sanitize(msg.getBody());

        CompletableFuture<Outcome> future = CompletableFuture.supplyAsync(() -> {
            GroupMemberEntity myMember = groupMemberMapper.selectOne(new LambdaQueryWrapper<GroupMemberEntity>()
                    .eq(GroupMemberEntity::getGroupId, groupId)
                    .eq(GroupMemberEntity::getUserId, fromUserId)
                    .last("limit 1"));
            if (myMember == null) {
                return Outcome.error("not_group_member");
            }

            LocalDateTime speakMuteUntil = myMember.getSpeakMuteUntil();
            if (speakMuteUntil != null && speakMuteUntil.isAfter(LocalDateTime.now())) {
                return Outcome.error("group_speak_muted");
            }

            ClientMsgIdIdempotency.Claim newClaim = new ClientMsgIdIdempotency.Claim();
            newClaim.setServerMsgId(serverMsgId);
            ClientMsgIdIdempotency.Claim existed = idempotency.putIfAbsent(idempotencyKey, newClaim);
            if (existed != null) {
                return Outcome.duplicate(existed.getServerMsgId());
            }

            Set<Long> memberIds = groupMemberIdsCache.get(groupId);
            if (memberIds == null) {
                List<GroupMemberEntity> members = groupMemberMapper.selectList(new LambdaQueryWrapper<GroupMemberEntity>()
                        .eq(GroupMemberEntity::getGroupId, groupId));
                memberIds = new HashSet<>();
                if (members != null) {
                    for (GroupMemberEntity m : members) {
                        if (m == null || m.getUserId() == null || m.getUserId() <= 0) {
                            continue;
                        }
                        memberIds.add(m.getUserId());
                    }
                }
                if (!memberIds.isEmpty()) {
                    groupMemberIdsCache.put(groupId, memberIds);
                }
            }

            MessageEntity messageEntity = new MessageEntity();
            messageEntity.setId(messageId);
            messageEntity.setServerMsgId(serverMsgId);
            messageEntity.setChatType(ChatType.GROUP);
            messageEntity.setGroupId(groupId);
            messageEntity.setFromUserId(fromUserId);
            MessageType messageType = MessageType.fromString(msg.getMsgType());
            messageEntity.setMsgType(messageType == null ? MessageType.TEXT : messageType);
            messageEntity.setStatus(MessageStatus.SAVED);
            messageEntity.setContent(sanitizedBody);
            messageEntity.setClientMsgId(clientMsgId);

            Map<Long, MentionType> importantTargets = resolveImportantTargets(msg, fromUserId, groupId, memberIds);

            messageService.save(messageEntity);
            groupService.update(new LambdaUpdateWrapper<com.miniim.domain.entity.GroupEntity>()
                    .eq(com.miniim.domain.entity.GroupEntity::getId, groupId)
                    .set(com.miniim.domain.entity.GroupEntity::getUpdatedAt, LocalDateTime.now()));

            if (!importantTargets.isEmpty()) {
                List<MessageMentionEntity> rows = new ArrayList<>();
                LocalDateTime now = LocalDateTime.now();
                for (Map.Entry<Long, MentionType> e : importantTargets.entrySet()) {
                    MessageMentionEntity mm = new MessageMentionEntity();
                    mm.setId(IdWorker.getId());
                    mm.setGroupId(groupId);
                    mm.setMessageId(messageId);
                    mm.setMentionedUserId(e.getKey());
                    mm.setMentionType(e.getValue());
                    mm.setCreatedAt(now);
                    rows.add(mm);
                }
                messageMentionMapper.insertBatch(rows);
            }
            return Outcome.ok(memberIds, importantTargets);
        }, dbExecutor).orTimeout(3, TimeUnit.SECONDS);

        future.whenComplete((out, error) -> {
            if (error != null) {
                log.error("save group message failed: {}", error.toString());
                try {
                    idempotency.remove(idempotencyKey);
                } catch (Exception e) {
                    log.debug("idem cleanup failed: key={}, err={}", idempotencyKey, e.toString());
                }
                ctx.executor().execute(() -> wsWriter.writeError(ctx, "internal_error", clientMsgId, serverMsgId));
                done.complete(null);
                return;
            }
            if (out == null) {
                ctx.executor().execute(() -> wsWriter.writeError(ctx, "internal_error", clientMsgId, serverMsgId));
                done.complete(null);
                return;
            }
            if (out.reason != null) {
                ctx.executor().execute(() -> wsWriter.writeError(ctx, out.reason, clientMsgId, null));
                done.complete(null);
                return;
            }
            if (out.duplicateServerMsgId != null) {
                ctx.executor().execute(() -> wsWriter.writeAck(ctx, fromUserId, clientMsgId, out.duplicateServerMsgId, AckType.SAVED.getDesc(), null));
                done.complete(null);
                return;
            }

            ctx.executor().execute(() -> wsWriter.writeAck(ctx, fromUserId, clientMsgId, serverMsgId, AckType.SAVED.getDesc(), sanitizedBody));

            WsEnvelope normal = new WsEnvelope();
            normal.type = "GROUP_CHAT";
            normal.from = fromUserId;
            normal.groupId = groupId;
            normal.clientMsgId = clientMsgId;
            normal.serverMsgId = serverMsgId;
            normal.msgType = msg.getMsgType();
            normal.body = sanitizedBody;
            normal.ts = ts;

            WsEnvelope important = new WsEnvelope();
            important.type = normal.type;
            important.from = normal.from;
            important.groupId = normal.groupId;
            important.clientMsgId = normal.clientMsgId;
            important.serverMsgId = normal.serverMsgId;
            important.msgType = normal.msgType;
            important.body = normal.body;
            important.ts = normal.ts;
            important.important = true;

            WsEnvelope normalNotify = new WsEnvelope();
            normalNotify.type = "GROUP_NOTIFY";
            normalNotify.from = fromUserId;
            normalNotify.groupId = groupId;
            normalNotify.serverMsgId = serverMsgId;
            normalNotify.ts = ts;

            WsEnvelope importantNotify = new WsEnvelope();
            importantNotify.type = normalNotify.type;
            importantNotify.from = normalNotify.from;
            importantNotify.groupId = normalNotify.groupId;
            importantNotify.serverMsgId = normalNotify.serverMsgId;
            importantNotify.ts = normalNotify.ts;
            importantNotify.important = true;

            groupChatDispatchService.dispatch(
                    out.memberIds,
                    fromUserId,
                    out.importantTargets.keySet(),
                    normal,
                    important,
                    normalNotify,
                    importantNotify
            );
            done.complete(null);
        });

        return done;
    }

    private static class Outcome {
        final Set<Long> memberIds;
        final Map<Long, MentionType> importantTargets;
        final String duplicateServerMsgId;
        final String reason;

        private Outcome(Set<Long> memberIds, Map<Long, MentionType> importantTargets, String duplicateServerMsgId, String reason) {
            this.memberIds = memberIds == null ? Set.of() : memberIds;
            this.importantTargets = importantTargets == null ? Map.of() : importantTargets;
            this.duplicateServerMsgId = duplicateServerMsgId;
            this.reason = reason;
        }

        static Outcome ok(Set<Long> memberIds, Map<Long, MentionType> importantTargets) {
            return new Outcome(memberIds, importantTargets, null, null);
        }

        static Outcome duplicate(String serverMsgId) {
            return new Outcome(Set.of(), Map.of(), serverMsgId, null);
        }

        static Outcome error(String reason) {
            return new Outcome(Set.of(), Map.of(), null, reason);
        }
    }

    private Map<Long, MentionType> resolveImportantTargets(WsEnvelope msg, Long fromUserId, Long groupId, Set<Long> memberIds) {
        Map<Long, MentionType> importantTargets = new LinkedHashMap<>();
        if (msg.getMentions() != null) {
            for (String rawUid : msg.getMentions()) {
                Long uid = parsePositiveLongOrNull(rawUid);
                if (uid == null) {
                    continue;
                }
                if (uid.equals(fromUserId)) {
                    continue;
                }
                if (!memberIds.contains(uid)) {
                    continue;
                }
                importantTargets.putIfAbsent(uid, MentionType.MENTION);
            }
        }

        if (msg.getReplyToServerMsgId() == null || msg.getReplyToServerMsgId().isBlank()) {
            return importantTargets;
        }

        MessageEntity replied = null;
        try {
            long rid = Long.parseLong(msg.getReplyToServerMsgId());
            replied = messageService.getById(rid);
        } catch (NumberFormatException ignore) {
            // ignore
        }
        if (replied == null) {
            replied = messageService.getOne(new LambdaQueryWrapper<MessageEntity>()
                    .eq(MessageEntity::getServerMsgId, msg.getReplyToServerMsgId())
                    .last("limit 1"));
        }
        if (replied != null && replied.getChatType() == ChatType.GROUP
                && replied.getGroupId() != null && replied.getGroupId().equals(groupId)) {
            Long repliedUserId = replied.getFromUserId();
            if (repliedUserId != null && repliedUserId > 0 && !repliedUserId.equals(fromUserId) && memberIds.contains(repliedUserId)) {
                importantTargets.put(repliedUserId, MentionType.REPLY);
            }
        }
        return importantTargets;
    }

    private boolean validate(ChannelHandlerContext ctx, WsEnvelope msg) {
        if (msg.getClientMsgId() == null || msg.getClientMsgId().isBlank()) {
            wsWriter.writeError(ctx, "missing_msg_id", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getGroupId() == null || msg.getGroupId() <= 0) {
            wsWriter.writeError(ctx, "missing_group_id", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getBody() == null || msg.getBody().isBlank()) {
            wsWriter.writeError(ctx, "missing_body", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getBody().length() > MAX_BODY_LEN) {
            wsWriter.writeError(ctx, "body_too_long", msg.getClientMsgId(), null);
            return false;
        }
        return true;
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
}
