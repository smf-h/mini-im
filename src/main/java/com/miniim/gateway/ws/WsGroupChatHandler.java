package com.miniim.gateway.ws;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.miniim.common.content.ForbiddenWordFilter;
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
    private final MessageMentionMapper messageMentionMapper;
    private final GroupService groupService;
    private final ForbiddenWordFilter forbiddenWordFilter;
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

        if (!validate(ctx, msg)) {
            return;
        }

        Long groupId = msg.getGroupId();
        GroupMemberEntity myMember = groupMemberMapper.selectOne(new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId)
                .eq(GroupMemberEntity::getUserId, fromUserId)
                .last("limit 1"));
        if (myMember == null) {
            wsWriter.writeError(ctx, "not_group_member", msg.getClientMsgId(), null);
            return;
        }

        LocalDateTime speakMuteUntil = myMember.getSpeakMuteUntil();
        if (speakMuteUntil != null && speakMuteUntil.isAfter(LocalDateTime.now())) {
            wsWriter.writeError(ctx, "group_speak_muted", msg.getClientMsgId(), null);
            return;
        }

        List<GroupMemberEntity> members = groupMemberMapper.selectList(new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId));
        Set<Long> memberIds = new HashSet<>();
        if (members != null) {
            for (GroupMemberEntity m : members) {
                if (m == null || m.getUserId() == null || m.getUserId() <= 0) {
                    continue;
                }
                memberIds.add(m.getUserId());
            }
        }

        final long ts = Instant.now().toEpochMilli();
        Long messageId = IdWorker.getId();
        String serverMsgId = String.valueOf(messageId);

        String idempotencyKey = idempotency.key(fromUserId.toString(), "GROUP_CHAT:" + groupId + ":" + msg.getClientMsgId());
        ClientMsgIdIdempotency.Claim newClaim = new ClientMsgIdIdempotency.Claim();
        newClaim.setServerMsgId(serverMsgId);
        ClientMsgIdIdempotency.Claim existed = idempotency.putIfAbsent(idempotencyKey, newClaim);
        if (existed != null) {
            wsWriter.writeAck(ctx, fromUserId, msg.getClientMsgId(), existed.getServerMsgId(), AckType.SAVED.getDesc(), null);
            return;
        }

        String sanitizedBody = forbiddenWordFilter.sanitize(msg.getBody());

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
        messageEntity.setClientMsgId(msg.getClientMsgId());

        Map<Long, MentionType> importantTargets = resolveImportantTargets(msg, fromUserId, groupId, memberIds);

        CompletableFuture<Void> saveFuture = CompletableFuture.runAsync(() -> {
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
        }, dbExecutor).orTimeout(3, TimeUnit.SECONDS);

        saveFuture.whenComplete((v, error) -> {
            if (error != null) {
                log.error("save group message failed: {}", error.toString());
                idempotency.remove(idempotencyKey);
                ctx.executor().execute(() -> wsWriter.writeError(ctx, "internal_error", msg.getClientMsgId(), serverMsgId));
                return;
            }

            ctx.executor().execute(() -> wsWriter.writeAck(ctx, fromUserId, msg.getClientMsgId(), serverMsgId, AckType.SAVED.getDesc(), sanitizedBody));

            for (Long uid : memberIds) {
                List<Channel> channels = sessionRegistry.getChannels(uid);
                if (channels == null || channels.isEmpty()) {
                    continue;
                }
                boolean important = importantTargets.containsKey(uid);
                for (Channel ch : channels) {
                    if (ch == null || !ch.isActive()) {
                        continue;
                    }
                    if (uid.equals(fromUserId) && ch == channel) {
                        continue;
                    }
                    WsEnvelope out = new WsEnvelope();
                    out.type = "GROUP_CHAT";
                    out.from = fromUserId;
                    out.groupId = groupId;
                    out.clientMsgId = msg.getClientMsgId();
                    out.serverMsgId = serverMsgId;
                    out.msgType = msg.getMsgType();
                    out.body = sanitizedBody;
                    out.ts = ts;
                    if (important) {
                        out.important = true;
                    }
                    wsWriter.write(ch, out);
                }
            }
        });
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
