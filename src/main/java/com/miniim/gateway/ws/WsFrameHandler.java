package com.miniim.gateway.ws;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.miniim.auth.service.JwtService;
import com.miniim.domain.entity.CallRecordEntity;
import com.miniim.domain.entity.FriendRelationEntity;
import com.miniim.domain.entity.GroupMemberEntity;
import com.miniim.domain.entity.MessageEntity;
import com.miniim.domain.entity.MessageMentionEntity;
import com.miniim.domain.enums.AckType;
import com.miniim.domain.enums.CallStatus;
import com.miniim.domain.enums.ChatType;
import com.miniim.domain.enums.FriendRequestStatus;
import com.miniim.domain.enums.MessageStatus;
import com.miniim.domain.enums.MessageType;
import com.miniim.domain.enums.MentionType;
import com.miniim.domain.entity.FriendRequestEntity;
import com.miniim.domain.mapper.GroupMemberMapper;
import com.miniim.domain.mapper.MessageMapper;
import com.miniim.domain.mapper.MessageMentionMapper;
import com.miniim.domain.service.CallRecordService;
import com.miniim.domain.service.ConversationService;
import com.miniim.domain.service.FriendRelationService;
import com.miniim.domain.service.FriendRequestService;
import com.miniim.domain.service.GroupService;
import com.miniim.domain.service.MessageService;
import com.miniim.domain.service.SingleChatMemberService;
import com.miniim.domain.service.SingleChatService;
import com.miniim.domain.service.UserService;
import com.miniim.gateway.session.CallRegistry;
import com.miniim.gateway.session.SessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Slf4j
public class WsFrameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final int MAX_BODY_LEN = 4096;
    private static final int MAX_SDP_LEN = 120_000;
    private static final int MAX_ICE_LEN = 4096;
    private static final int CALL_RING_TIMEOUT_SEC = 30;

    private final ObjectMapper objectMapper;
    private final JwtService jwtService;
    private final SessionRegistry sessionRegistry;
    private final MessageService messageService;
    private final MessageMapper messageMapper;
    private final FriendRequestService friendRequestService;
    private final ConversationService conversationService;
    private  final SingleChatService singleChatService;
    private final SingleChatMemberService singleChatMemberService;
    private final GroupMemberMapper groupMemberMapper;
    private final MessageMentionMapper messageMentionMapper;
    private final GroupService groupService;
    private final Executor dbExecutor;
    private final ClientMsgIdIdempotency idempotency;
    private final UserService userService;
    private final CallRegistry callRegistry;
    private final CallRecordService callRecordService;
    private final FriendRelationService friendRelationService;
    public WsFrameHandler(ObjectMapper objectMapper,
                          JwtService jwtService,
                          SessionRegistry sessionRegistry,
                          MessageService messageService,
                          MessageMapper messageMapper,
                          FriendRequestService friendRequestService,
                          ConversationService conversationService,
                          SingleChatService singleChatService,
                          SingleChatMemberService singleChatMemberService,
                          GroupMemberMapper groupMemberMapper,
                          MessageMentionMapper messageMentionMapper,
                          GroupService groupService,
                          Executor dbExecutor,
                          ClientMsgIdIdempotency idempotency,
                          UserService userService,
                          CallRegistry callRegistry,
                          CallRecordService callRecordService,
                          FriendRelationService friendRelationService) {
        this.objectMapper = objectMapper;
        this.jwtService = jwtService;
        this.sessionRegistry = sessionRegistry;
        this.messageService = messageService;
        this.messageMapper = messageMapper;
        this.friendRequestService = friendRequestService;
        this.conversationService = conversationService;
        this.singleChatService = singleChatService;
        this.singleChatMemberService = singleChatMemberService;
        this.groupMemberMapper = groupMemberMapper;
        this.messageMentionMapper = messageMentionMapper;
        this.groupService = groupService;
        this.dbExecutor = dbExecutor;
        this.idempotency = idempotency;
        this.userService = userService;
        this.callRegistry = callRegistry;
        this.callRecordService = callRecordService;
        this.friendRelationService = friendRelationService;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception {
        // 我们约定：客户端发来的每一个 TextWebSocketFrame 都是一段 JSON
        // 用 WsEnvelope 这个“信封对象”承载：type + token + from/to + body + ts ...
        String raw = frame.text();
        WsEnvelope msg;
        try {
            msg = objectMapper.readValue(raw, WsEnvelope.class);
            if (msg.getType() != null && msg.getType().startsWith("CALL_")) {
                Long uid = ctx.channel().attr(SessionRegistry.ATTR_USER_ID).get();
                log.info("received call envelope type={}, userId={}, callId={}", msg.getType(), uid, msg.getCallId());
            } else {
                log.info("received frame {}", redactToken(raw));
                log.info("received envelope type={}, clientMsgId={}, serverMsgId={}, from={}, to={}",
                        msg.getType(), msg.getClientMsgId(), msg.getServerMsgId(), msg.getFrom(), msg.getTo());
            }
        } catch (Exception e) {
            writeError(ctx, "bad_json");
            return;
        }

        if (msg.type == null) {
            writeError(ctx, "missing_type");
            return;
        }

        // 除了 AUTH/REAUTH 以外，其他消息都要求：已鉴权且 accessToken 未过期。
        if (!"AUTH".equals(msg.type) && !"REAUTH".equals(msg.type)) {
            if (!sessionRegistry.isAuthed(ctx.channel())) {
                writeError(ctx, "unauthorized");
                ctx.close();
                return;
            }
            if (isExpired(ctx)) {
                writeError(ctx, "token_expired");
                ctx.close();
                return;
            }
        }

        switch (msg.type) {
            case "AUTH" -> handleAuth(ctx, msg);
            case "REAUTH" -> handleReauth(ctx, msg);
            case "PING" -> handlePing(ctx);
            case "SINGLE_CHAT" -> handleSingleChat(ctx, msg);
            case "GROUP_CHAT" -> handleGroupChat(ctx, msg);
            case "FRIEND_REQUEST" -> handleFriendRequest(ctx, msg);
            case "ACK" -> handleAck(ctx, msg);
            case "CALL_INVITE" -> handleCallInvite(ctx, msg);
            case "CALL_ACCEPT" -> handleCallAccept(ctx, msg);
            case "CALL_REJECT" -> handleCallReject(ctx, msg);
            case "CALL_CANCEL" -> handleCallCancel(ctx, msg);
            case "CALL_END" -> handleCallEnd(ctx, msg);
            case "CALL_ICE" -> handleCallIce(ctx, msg);
            default -> {
                writeError(ctx, "not_implemented", msg.getClientMsgId(), null);
            }
        }
    }

    private void handleFriendRequest(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
            writeError(ctx, "unauthorized", msg.getClientMsgId(), null);
            return;
        }
        if (!validateFriendRequest(ctx, msg)) {
            return;
        }

        Long toUserId = msg.getTo();
        String clientMsgId = msg.getClientMsgId();

        // 同一 clientMsgId 的重试：返回同一个 requestId（serverMsgId）
        String key = idempotency.key(fromUserId.toString(), "FRIEND_REQUEST:" + clientMsgId);
        ClientMsgIdIdempotency.Claim newClaim = new ClientMsgIdIdempotency.Claim();
        String requestId = String.valueOf(IdWorker.getId());
        newClaim.setServerMsgId(requestId);
        ClientMsgIdIdempotency.Claim claim = idempotency.putIfAbsent(key, newClaim);
        if (claim != null) {
            writeAck(ctx, fromUserId, clientMsgId, claim.getServerMsgId(), AckType.SAVED.getDesc());
            return;
        }

        FriendRequestEntity entity = new FriendRequestEntity();
        entity.setId(Long.valueOf(requestId));
        entity.setFromUserId(fromUserId);
        entity.setToUserId(toUserId);
        entity.setContent(msg.getBody());
        entity.setStatus(FriendRequestStatus.PENDING);

        CompletableFuture<Boolean> saveFuture = CompletableFuture
                .supplyAsync(() -> friendRequestService.save(entity), dbExecutor)
                .orTimeout(3, TimeUnit.SECONDS);

        saveFuture.whenComplete((ok, error) -> {
            if (error != null || ok == null || !ok) {
                idempotency.remove(key);
                log.error("save friend request failed: {}", error == null ? "save_returned_false" : error.toString());
                ctx.executor().execute(() -> writeError(ctx, "internal_error", clientMsgId, requestId));
                return;
            }

            ctx.executor().execute(() -> writeAck(ctx, fromUserId, clientMsgId, requestId, AckType.SAVED.getDesc()));

            // best-effort 推送一次给接收方：收不到就算（不重试）
            WsEnvelope out = new WsEnvelope();
            out.setType("FRIEND_REQUEST");
            out.setFrom(fromUserId);
            out.setTo(toUserId);
            out.setClientMsgId(clientMsgId);
            out.setServerMsgId(requestId);
            out.setBody(msg.getBody());
            out.setTs(Instant.now().toEpochMilli());
            for (Channel chTo : sessionRegistry.getChannels(toUserId)) {
                if (chTo == null || !chTo.isActive()) {
                    continue;
                }
                final Channel target = chTo;
                target.eventLoop().execute(() -> write(target, out));
            }
        });
    }

    private boolean validateFriendRequest(ChannelHandlerContext ctx, WsEnvelope msg) {
        if (msg.getClientMsgId() == null || msg.getClientMsgId().isBlank()) {
            writeError(ctx, "missing_msg_id", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getTo() == null) {
            writeError(ctx, "missing_to", msg.getClientMsgId(), null);
            return false;
        }
        Long fromUserId = ctx.channel().attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId != null && msg.getTo().equals(fromUserId)) {
            writeError(ctx, "cannot_send_to_self", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getBody() != null && msg.getBody().length() > 256) {
            writeError(ctx, "body_too_long", msg.getClientMsgId(), null);
            return false;
        }
        return true;
    }

    private static String redactToken(String raw) {
        if (raw == null) {
            return null;
        }
        String needle = "\"token\":\"";
        int idx = raw.indexOf(needle);
        if (idx < 0) {
            return raw;
        }
        int start = idx + needle.length();
        int end = raw.indexOf('"', start);
        if (end < 0) {
            return raw;
        }
        return raw.substring(0, start) + "***" + raw.substring(end);
    }

    private void handleSingleChat(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
             writeError(ctx, "unauthorized", msg.getClientMsgId(), null);
             return;
        }
        if (!validateSingleChat(ctx, msg)) {
            writeError(ctx, "invalid_single_chat", msg.getClientMsgId(), null);
            return;
        }
        Long toUserId = msg.getTo();
        if (toUserId.equals(fromUserId)) {
             writeError(ctx, "cannot_send_to_self", msg.getClientMsgId(), null);
             return;
        }
        List<Channel> channelsTo = sessionRegistry.getChannels(toUserId);
//        if (channelTo == null) {
//             writeError(ctx, "channel_not_found", msg.getClientMsgId(), null);
//        }
//        if (channelTo!=null&&!channelTo.isActive()) {
//             writeError(ctx, "target_channel_inactive", msg.getClientMsgId(), null);
//        }
        final boolean dropped = channelsTo.stream().noneMatch(ch -> ch != null && ch.isActive());
        // 使用 Hutool：先拷贝出新对象，再修改新对象（避免多线程/异步回调里修改入参 msg）。
        final WsEnvelope base = BeanUtil.toBean(msg, WsEnvelope.class);
        base.setFrom(fromUserId);
        base.setTs(Instant.now().toEpochMilli());

        Long user1Id = Math.min(fromUserId, toUserId);
        Long user2Id = Math.max(fromUserId, toUserId);
        Long messageId = IdWorker.getId();
        String serverMsgId = String.valueOf(messageId);
        String key=idempotency.key(fromUserId.toString(),msg.getClientMsgId());
        ClientMsgIdIdempotency.Claim newClaim = new ClientMsgIdIdempotency.Claim();
        newClaim.setServerMsgId(serverMsgId);
        ClientMsgIdIdempotency.Claim claim = idempotency.putIfAbsent(key,newClaim);
        if (claim!=null) {
            writeAck(ctx,fromUserId, msg.getClientMsgId(),claim.getServerMsgId(), AckType.SAVED.getDesc());
            return;
        }
        MessageEntity messageEntity = new MessageEntity();
        messageEntity.setId(messageId);
        messageEntity.setServerMsgId(serverMsgId);
        messageEntity.setChatType(ChatType.SINGLE);
        messageEntity.setFromUserId(fromUserId);
        messageEntity.setToUserId(toUserId);
        MessageType messageType = MessageType.fromString(msg.getMsgType());
        messageEntity.setMsgType(messageType == null ? MessageType.TEXT : messageType);
        messageEntity.setStatus(MessageStatus.SAVED);
        messageEntity.setContent(msg.getBody());
        messageEntity.setClientMsgId(msg.getClientMsgId());
        base.setServerMsgId(serverMsgId);
        // 注意：这里的 save 是阻塞式 DB 操作；生产建议放到业务线程池执行，避免阻塞 Netty eventLoop。
        CompletableFuture<Long> saveFuture = CompletableFuture.supplyAsync(() ->{
            Long singleChatId =singleChatService.getOrCreateSingleChatId(user1Id, user2Id);
            messageEntity.setSingleChatId(singleChatId);
            singleChatMemberService.ensureMembers(singleChatId, fromUserId, toUserId);
            messageService.save(messageEntity);
            // 会话列表按更新时间排序：消息落库后 touch 更新会话 updatedAt
            singleChatService.update(new LambdaUpdateWrapper<com.miniim.domain.entity.SingleChatEntity>()
                    .eq(com.miniim.domain.entity.SingleChatEntity::getId, singleChatId)
                    .set(com.miniim.domain.entity.SingleChatEntity::getUpdatedAt, LocalDateTime.now()));
            return messageEntity.getId();
            }, dbExecutor).orTimeout(3,TimeUnit.SECONDS);
        saveFuture.whenComplete((result,error)->{
            if(error!=null){
                log.error("save message failed: {}", error.toString());
                idempotency.remove(key);
               ctx.executor().execute(() -> writeError(ctx, "internal_error", msg.getClientMsgId(), serverMsgId));
                return;
            }
            else{
            ctx.executor().execute(() -> writeAck(ctx, fromUserId, base.getClientMsgId(), serverMsgId, AckType.SAVED.getDesc()));
            }
            if (dropped) {
                return;
            }

            for (Channel chTo : channelsTo) {
                if (chTo == null || !chTo.isActive()) {
                    continue;
                }
                final Channel target = chTo;
                target.eventLoop().execute(() -> {
                    ChannelFuture future;
                    try {
                        WsEnvelope out = BeanUtil.toBean(base, WsEnvelope.class);
                        out.setType("SINGLE_CHAT");
                        // 明确下发类型（防止客户端乱填/或未来扩展复用 envelope）
                        out.setType("SINGLE_CHAT");
                        future = write(target, out);
                    } catch (Exception e) {
                        ctx.executor().execute(() -> writeError(ctx, "internal_error", base.getClientMsgId(), serverMsgId));
                        return;
                    }
                    future.addListener(f -> {
                        if (f.isSuccess()) {
                            // 更新消息状态为 DELIVERED
                            // deliver 状态当前不启用：消息保持 SAVED，直到收到收件人 ACK_RECEIVED 才推进到 RECEIVED
                        } else {
                            log.error("deliver message to user {} failed: {}", toUserId, f.cause().toString());
                            ctx.executor().execute(() -> writeError(ctx, "deliver_failed", base.getClientMsgId(), serverMsgId));
                        }
                    });
                });
            }
        });
    }

    private void handleGroupChat(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
            writeError(ctx, "unauthorized", msg.getClientMsgId(), msg.getServerMsgId());
            ctx.close();
            return;
        }

        if (!validateGroupChat(ctx, msg)) {
            return;
        }

        Long groupId = msg.getGroupId();
        long memberCnt = groupMemberMapper.selectCount(new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId)
                .eq(GroupMemberEntity::getUserId, fromUserId));
        if (memberCnt <= 0) {
            writeError(ctx, "not_group_member", msg.getClientMsgId(), null);
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
            writeAck(ctx, fromUserId, msg.getClientMsgId(), existed.getServerMsgId(), AckType.SAVED.getDesc());
            return;
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
        messageEntity.setContent(msg.getBody());
        messageEntity.setClientMsgId(msg.getClientMsgId());

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

        if (msg.getReplyToServerMsgId() != null && !msg.getReplyToServerMsgId().isBlank()) {
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
        }

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
                ctx.executor().execute(() -> writeError(ctx, "internal_error", msg.getClientMsgId(), serverMsgId));
                return;
            }

            ctx.executor().execute(() -> writeAck(ctx, fromUserId, msg.getClientMsgId(), serverMsgId, AckType.SAVED.getDesc()));

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
                    out.body = msg.getBody();
                    out.ts = ts;
                    if (important) {
                        out.important = true;
                    }
                    ch.eventLoop().execute(() -> write(ch, out));
                }
            }
        });
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

    private void handleAck(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
            writeError(ctx, "unauthorized", msg.getClientMsgId(), msg.getServerMsgId());
            ctx.close();
            return ;
        }

        if (!validateAck(ctx, msg)) {
            return ;
        }

        msg.setFrom(fromUserId);
        msg.setTs(Instant.now().toEpochMilli());

        Long toUserId = msg.getTo();
        Channel target = sessionRegistry.getChannel(toUserId);
//        if (target == null || !target.isActive()) {
//            writeError(ctx, "ack_target_offline", msg.getClientMsgId(), msg.getServerMsgId());
//            return false;
//        }

	        String ackType = msg.getAckType();
	        if (isDeliveredAck(ackType)) {
	            handleAckAdvanceCursor(ctx, msg, fromUserId, AckType.DELIVERED);
	        } else if (isReadAck(ackType)) {
	            handleAckAdvanceCursor(ctx, msg, fromUserId, AckType.READ);
	        }

        // 未处理其他类型
        return ;
    }

    private void handleCallInvite(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
            writeCallError(ctx, null, "unauthorized", null, msg.getClientMsgId());
            return;
        }
        if (!validateCallInvite(ctx, msg)) {
            return;
        }

        long callerUserId = fromUserId;
        long calleeUserId = msg.getTo();
        if (callerUserId == calleeUserId) {
            writeCallError(ctx, null, "cannot_call_self", null, msg.getClientMsgId());
            return;
        }

        boolean friend = friendRelationService.count(new LambdaQueryWrapper<FriendRelationEntity>()
                .nested(w -> w.eq(FriendRelationEntity::getUser1Id, callerUserId).eq(FriendRelationEntity::getUser2Id, calleeUserId)
                        .or()
                        .eq(FriendRelationEntity::getUser1Id, calleeUserId).eq(FriendRelationEntity::getUser2Id, callerUserId))) > 0;
        if (!friend) {
            writeCallError(ctx, null, "not_friend", null, msg.getClientMsgId());
            return;
        }

        if (callRegistry.isBusy(callerUserId) || callRegistry.isBusy(calleeUserId)) {
            long callId = IdWorker.getId();
            persistCallFailed(callId, callerUserId, calleeUserId, "busy");
            writeCallError(ctx, callId, "busy", "busy", msg.getClientMsgId());
            return;
        }

        List<Channel> calleeChannels = sessionRegistry.getChannels(calleeUserId);
        boolean calleeOnline = calleeChannels != null && calleeChannels.stream().anyMatch(ch -> ch != null && ch.isActive());
        if (!calleeOnline) {
            long callId = IdWorker.getId();
            persistCallFailed(callId, callerUserId, calleeUserId, "offline");
            writeCallError(ctx, callId, "callee_offline", "offline", msg.getClientMsgId());
            return;
        }

        long callId = IdWorker.getId();
        CallRegistry.CallSession session = callRegistry.tryCreate(callId, callerUserId, calleeUserId);
        if (session == null) {
            persistCallFailed(callId, callerUserId, calleeUserId, "busy");
            writeCallError(ctx, callId, "busy", "busy", msg.getClientMsgId());
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        CompletableFuture<Boolean> saveFuture = CompletableFuture
                .supplyAsync(() -> {
                    CallRecordEntity record = new CallRecordEntity();
                    record.setCallId(callId);
                    record.setCallerUserId(callerUserId);
                    record.setCalleeUserId(calleeUserId);
                    record.setStatus(CallStatus.RINGING);
                    record.setFailReason(null);
                    record.setStartedAt(now);
                    return callRecordService.save(record);
                }, dbExecutor)
                .orTimeout(3, TimeUnit.SECONDS);

        saveFuture.whenComplete((ok, error) -> {
            if (error != null || ok == null || !ok) {
                callRegistry.clear(callId);
                ctx.executor().execute(() -> writeCallError(ctx, callId, "internal_error", null, msg.getClientMsgId()));
                return;
            }

            session.setTimeoutFuture(ctx.executor().schedule(() -> handleCallTimeout(callId), CALL_RING_TIMEOUT_SEC, TimeUnit.SECONDS));

            ctx.executor().execute(() -> {
                WsEnvelope ack = new WsEnvelope();
                ack.setType("CALL_INVITE_OK");
                ack.setFrom(callerUserId);
                ack.setTo(calleeUserId);
                ack.setCallId(callId);
                ack.setCallKind(msg.getCallKind());
                ack.setClientMsgId(msg.getClientMsgId());
                ack.setTs(Instant.now().toEpochMilli());
                write(ctx, ack);
            });

            WsEnvelope incoming = new WsEnvelope();
            incoming.setType("CALL_INVITE");
            incoming.setFrom(callerUserId);
            incoming.setTo(calleeUserId);
            incoming.setCallId(callId);
            incoming.setCallKind(msg.getCallKind());
            incoming.setSdp(msg.getSdp());
            incoming.setTs(Instant.now().toEpochMilli());
            forwardToUser(calleeUserId, incoming);
        });
    }

    private void handleCallAccept(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
            writeCallError(ctx, msg.getCallId(), "unauthorized", null, msg.getClientMsgId());
            return;
        }
        if (!validateCallAccept(ctx, msg)) {
            return;
        }
        Long callId = msg.getCallId();
        if (callId == null) {
            writeCallError(ctx, null, "missing_call_id", null, msg.getClientMsgId());
            return;
        }
        CallRegistry.CallSession session = callRegistry.get(callId);
        if (session == null) {
            writeCallError(ctx, callId, "call_not_found", null, msg.getClientMsgId());
            return;
        }
        long userId = fromUserId;
        if (!session.isParticipant(userId)) {
            writeCallError(ctx, callId, "call_not_participant", null, msg.getClientMsgId());
            return;
        }
        if (userId != session.getCalleeUserId()) {
            writeCallError(ctx, callId, "only_callee_can_accept", null, msg.getClientMsgId());
            return;
        }
        if (session.getStatus() != CallStatus.RINGING) {
            writeCallError(ctx, callId, "call_not_ringing", null, msg.getClientMsgId());
            return;
        }

        session.setStatus(CallStatus.ACCEPTED);
        session.markAccepted();
        LocalDateTime now = LocalDateTime.now();
        CompletableFuture<Boolean> updateFuture = CompletableFuture
                .supplyAsync(() -> callRecordService.update(new LambdaUpdateWrapper<CallRecordEntity>()
                        .eq(CallRecordEntity::getCallId, callId)
                        .set(CallRecordEntity::getStatus, CallStatus.ACCEPTED)
                        .set(CallRecordEntity::getAcceptedAt, now)), dbExecutor)
                .orTimeout(3, TimeUnit.SECONDS);

        updateFuture.whenComplete((ok, error) -> {
            if (error != null || ok == null || !ok) {
                ctx.executor().execute(() -> writeCallError(ctx, callId, "internal_error", null, msg.getClientMsgId()));
                return;
            }

            long peer = session.peerOf(userId);
            WsEnvelope out = new WsEnvelope();
            out.setType("CALL_ACCEPT");
            out.setFrom(userId);
            out.setTo(peer);
            out.setCallId(callId);
            out.setCallKind(msg.getCallKind());
            out.setSdp(msg.getSdp());
            out.setTs(Instant.now().toEpochMilli());
            forwardToUser(peer, out);
        });
    }

    private void handleCallReject(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
            writeCallError(ctx, msg.getCallId(), "unauthorized", null, msg.getClientMsgId());
            return;
        }
        if (!validateCallBasic(ctx, msg)) {
            return;
        }
        Long callId = msg.getCallId();
        if (callId == null) {
            writeCallError(ctx, null, "missing_call_id", null, msg.getClientMsgId());
            return;
        }
        CallRegistry.CallSession session = callRegistry.get(callId);
        if (session == null) {
            writeCallError(ctx, callId, "call_not_found", null, msg.getClientMsgId());
            return;
        }
        long userId = fromUserId;
        if (userId != session.getCalleeUserId()) {
            writeCallError(ctx, callId, "only_callee_can_reject", null, msg.getClientMsgId());
            return;
        }
        if (session.getStatus() != CallStatus.RINGING) {
            writeCallError(ctx, callId, "call_not_ringing", null, msg.getClientMsgId());
            return;
        }

        session.setStatus(CallStatus.REJECTED);
        LocalDateTime now = LocalDateTime.now();
        CompletableFuture<Boolean> updateFuture = CompletableFuture
                .supplyAsync(() -> callRecordService.update(new LambdaUpdateWrapper<CallRecordEntity>()
                        .eq(CallRecordEntity::getCallId, callId)
                        .set(CallRecordEntity::getStatus, CallStatus.REJECTED)
                        .set(CallRecordEntity::getFailReason, safeCallReason(msg.getCallReason()))
                        .set(CallRecordEntity::getEndedAt, now)), dbExecutor)
                .orTimeout(3, TimeUnit.SECONDS);

        updateFuture.whenComplete((ok, error) -> {
            callRegistry.clear(callId);
            if (error != null || ok == null || !ok) {
                ctx.executor().execute(() -> writeCallError(ctx, callId, "internal_error", null, msg.getClientMsgId()));
                return;
            }
            long peer = session.peerOf(userId);
            WsEnvelope out = new WsEnvelope();
            out.setType("CALL_REJECT");
            out.setFrom(userId);
            out.setTo(peer);
            out.setCallId(callId);
            out.setCallReason(safeCallReason(msg.getCallReason()));
            out.setTs(Instant.now().toEpochMilli());
            forwardToUser(peer, out);
        });
    }

    private void handleCallCancel(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
            writeCallError(ctx, msg.getCallId(), "unauthorized", null, msg.getClientMsgId());
            return;
        }
        if (!validateCallBasic(ctx, msg)) {
            return;
        }
        Long callId = msg.getCallId();
        if (callId == null) {
            writeCallError(ctx, null, "missing_call_id", null, msg.getClientMsgId());
            return;
        }
        CallRegistry.CallSession session = callRegistry.get(callId);
        if (session == null) {
            writeCallError(ctx, callId, "call_not_found", null, msg.getClientMsgId());
            return;
        }
        long userId = fromUserId;
        if (userId != session.getCallerUserId()) {
            writeCallError(ctx, callId, "only_caller_can_cancel", null, msg.getClientMsgId());
            return;
        }
        if (session.getStatus() != CallStatus.RINGING) {
            writeCallError(ctx, callId, "call_not_ringing", null, msg.getClientMsgId());
            return;
        }

        session.setStatus(CallStatus.CANCELED);
        LocalDateTime now = LocalDateTime.now();
        CompletableFuture<Boolean> updateFuture = CompletableFuture
                .supplyAsync(() -> callRecordService.update(new LambdaUpdateWrapper<CallRecordEntity>()
                        .eq(CallRecordEntity::getCallId, callId)
                        .set(CallRecordEntity::getStatus, CallStatus.CANCELED)
                        .set(CallRecordEntity::getFailReason, safeCallReason(msg.getCallReason()))
                        .set(CallRecordEntity::getEndedAt, now)), dbExecutor)
                .orTimeout(3, TimeUnit.SECONDS);

        updateFuture.whenComplete((ok, error) -> {
            callRegistry.clear(callId);
            if (error != null || ok == null || !ok) {
                ctx.executor().execute(() -> writeCallError(ctx, callId, "internal_error", null, msg.getClientMsgId()));
                return;
            }
            long peer = session.peerOf(userId);
            WsEnvelope out = new WsEnvelope();
            out.setType("CALL_CANCEL");
            out.setFrom(userId);
            out.setTo(peer);
            out.setCallId(callId);
            out.setCallReason(safeCallReason(msg.getCallReason()));
            out.setTs(Instant.now().toEpochMilli());
            forwardToUser(peer, out);
        });
    }

    private void handleCallEnd(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
            writeCallError(ctx, msg.getCallId(), "unauthorized", null, msg.getClientMsgId());
            return;
        }
        if (!validateCallBasic(ctx, msg)) {
            return;
        }
        Long callId = msg.getCallId();
        if (callId == null) {
            writeCallError(ctx, null, "missing_call_id", null, msg.getClientMsgId());
            return;
        }
        CallRegistry.CallSession session = callRegistry.get(callId);
        if (session == null) {
            writeCallError(ctx, callId, "call_not_found", null, msg.getClientMsgId());
            return;
        }
        long userId = fromUserId;
        if (!session.isParticipant(userId)) {
            writeCallError(ctx, callId, "call_not_participant", null, msg.getClientMsgId());
            return;
        }

        CallStatus nextStatus = session.getStatus() == CallStatus.ACCEPTED ? CallStatus.ENDED : (userId == session.getCallerUserId() ? CallStatus.CANCELED : CallStatus.REJECTED);
        LocalDateTime now = LocalDateTime.now();
        Integer durationSeconds = null;
        if (nextStatus == CallStatus.ENDED) {
            Long acceptedAtMs = session.getAcceptedAtMs();
            if (acceptedAtMs != null) {
                long d = Math.max(0, (Instant.now().toEpochMilli() - acceptedAtMs) / 1000);
                durationSeconds = (int) Math.min(d, Integer.MAX_VALUE);
            }
        }
        final Integer durationSecondsFinal = durationSeconds;

        CompletableFuture<Boolean> updateFuture = CompletableFuture
                .supplyAsync(() -> {
                    LambdaUpdateWrapper<CallRecordEntity> w = new LambdaUpdateWrapper<CallRecordEntity>()
                            .eq(CallRecordEntity::getCallId, callId)
                            .set(CallRecordEntity::getStatus, nextStatus)
                            .set(CallRecordEntity::getFailReason, safeCallReason(msg.getCallReason()))
                            .set(CallRecordEntity::getEndedAt, now);
                    if (durationSecondsFinal != null) {
                        w.set(CallRecordEntity::getDurationSeconds, durationSecondsFinal);
                    }
                    return callRecordService.update(w);
                }, dbExecutor)
                .orTimeout(3, TimeUnit.SECONDS);

        updateFuture.whenComplete((ok, error) -> {
            callRegistry.clear(callId);
            if (error != null || ok == null || !ok) {
                ctx.executor().execute(() -> writeCallError(ctx, callId, "internal_error", null, msg.getClientMsgId()));
                return;
            }
            long peer = session.peerOf(userId);
            WsEnvelope out = new WsEnvelope();
            out.setType("CALL_END");
            out.setFrom(userId);
            out.setTo(peer);
            out.setCallId(callId);
            out.setCallReason(safeCallReason(msg.getCallReason()));
            out.setTs(Instant.now().toEpochMilli());
            forwardToUser(peer, out);
        });
    }

    private void handleCallIce(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel channel = ctx.channel();
        Long fromUserId = channel.attr(SessionRegistry.ATTR_USER_ID).get();
        if (fromUserId == null) {
            writeCallError(ctx, msg.getCallId(), "unauthorized", null, msg.getClientMsgId());
            return;
        }
        if (!validateCallIce(ctx, msg)) {
            return;
        }
        Long callId = msg.getCallId();
        CallRegistry.CallSession session = callRegistry.get(callId);
        if (session == null) {
            writeCallError(ctx, callId, "call_not_found", null, msg.getClientMsgId());
            return;
        }
        long userId = fromUserId;
        if (!session.isParticipant(userId)) {
            writeCallError(ctx, callId, "call_not_participant", null, msg.getClientMsgId());
            return;
        }

        long peer = session.peerOf(userId);
        WsEnvelope out = new WsEnvelope();
        out.setType("CALL_ICE");
        out.setFrom(userId);
        out.setTo(peer);
        out.setCallId(callId);
        out.setIceCandidate(msg.getIceCandidate());
        out.setIceSdpMid(msg.getIceSdpMid());
        out.setIceSdpMLineIndex(msg.getIceSdpMLineIndex());
        out.setTs(Instant.now().toEpochMilli());
        forwardToUser(peer, out);
    }

    private void handleCallTimeout(long callId) {
        CallRegistry.CallSession session = callRegistry.get(callId);
        if (session == null) {
            return;
        }
        if (session.getStatus() != CallStatus.RINGING) {
            return;
        }
        session.setStatus(CallStatus.MISSED);
        LocalDateTime now = LocalDateTime.now();
        CompletableFuture<Boolean> updateFuture = CompletableFuture
                .supplyAsync(() -> callRecordService.update(new LambdaUpdateWrapper<CallRecordEntity>()
                        .eq(CallRecordEntity::getCallId, callId)
                        .set(CallRecordEntity::getStatus, CallStatus.MISSED)
                        .set(CallRecordEntity::getFailReason, "timeout")
                        .set(CallRecordEntity::getEndedAt, now)), dbExecutor);
        updateFuture.whenComplete((ok, error) -> callRegistry.clear(callId));

        WsEnvelope out = new WsEnvelope();
        out.setType("CALL_TIMEOUT");
        out.setCallId(callId);
        out.setCallReason("timeout");
        out.setTs(Instant.now().toEpochMilli());
        forwardToUser(session.getCallerUserId(), out);
        forwardToUser(session.getCalleeUserId(), out);
    }

    private void forwardToUser(long userId, WsEnvelope env) {
        List<Channel> channels = sessionRegistry.getChannels(userId);
        if (channels == null || channels.isEmpty()) {
            return;
        }
        for (Channel ch : channels) {
            if (ch == null || !ch.isActive()) {
                continue;
            }
            final Channel target = ch;
            target.eventLoop().execute(() -> write(target, env));
        }
    }

    private void persistCallFailed(long callId, long callerUserId, long calleeUserId, String failReason) {
        LocalDateTime now = LocalDateTime.now();
        CompletableFuture.runAsync(() -> {
            CallRecordEntity record = new CallRecordEntity();
            record.setCallId(callId);
            record.setCallerUserId(callerUserId);
            record.setCalleeUserId(calleeUserId);
            record.setStatus(CallStatus.FAILED);
            record.setFailReason(failReason);
            record.setStartedAt(now);
            record.setEndedAt(now);
            callRecordService.save(record);
        }, dbExecutor);
    }

    private static String safeCallReason(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.length() > 64) {
            return s.substring(0, 64);
        }
        return s;
    }

    private boolean validateCallInvite(ChannelHandlerContext ctx, WsEnvelope msg) {
        if (msg.getTo() == null) {
            writeCallError(ctx, null, "missing_to", null, msg.getClientMsgId());
            return false;
        }
        String kind = msg.getCallKind();
        if (kind == null || kind.isBlank()) {
            msg.setCallKind("video");
        }
        if (!"video".equalsIgnoreCase(msg.getCallKind())) {
            writeCallError(ctx, null, "unsupported_call_kind", null, msg.getClientMsgId());
            return false;
        }
        if (msg.getSdp() == null || msg.getSdp().isBlank()) {
            writeCallError(ctx, null, "missing_sdp", null, msg.getClientMsgId());
            return false;
        }
        if (msg.getSdp().length() > MAX_SDP_LEN) {
            writeCallError(ctx, null, "sdp_too_long", null, msg.getClientMsgId());
            return false;
        }
        return true;
    }

    private boolean validateCallAccept(ChannelHandlerContext ctx, WsEnvelope msg) {
        if (!validateCallBasic(ctx, msg)) {
            return false;
        }
        if (msg.getSdp() == null || msg.getSdp().isBlank()) {
            writeCallError(ctx, msg.getCallId(), "missing_sdp", null, msg.getClientMsgId());
            return false;
        }
        if (msg.getSdp().length() > MAX_SDP_LEN) {
            writeCallError(ctx, msg.getCallId(), "sdp_too_long", null, msg.getClientMsgId());
            return false;
        }
        return true;
    }

    private boolean validateCallIce(ChannelHandlerContext ctx, WsEnvelope msg) {
        if (!validateCallBasic(ctx, msg)) {
            return false;
        }
        if (msg.getIceCandidate() == null || msg.getIceCandidate().isBlank()) {
            writeCallError(ctx, msg.getCallId(), "missing_ice_candidate", null, msg.getClientMsgId());
            return false;
        }
        if (msg.getIceCandidate().length() > MAX_ICE_LEN) {
            writeCallError(ctx, msg.getCallId(), "ice_candidate_too_long", null, msg.getClientMsgId());
            return false;
        }
        return true;
    }

    private boolean validateCallBasic(ChannelHandlerContext ctx, WsEnvelope msg) {
        if (msg.getCallId() == null || msg.getCallId() <= 0) {
            writeCallError(ctx, null, "missing_call_id", null, msg.getClientMsgId());
            return false;
        }
        return true;
    }

    private ChannelFuture writeCallError(ChannelHandlerContext ctx, Long callId, String reason, String callReason, String clientMsgId) {
        WsEnvelope err = new WsEnvelope();
        err.setType("CALL_ERROR");
        err.setCallId(callId);
        err.setClientMsgId(clientMsgId);
        err.setReason(reason);
        err.setCallReason(callReason);
        err.setTs(Instant.now().toEpochMilli());
        return write(ctx, err);
    }

    private static boolean isDeliveredAck(String ackType) {
        if (ackType == null) {
            return false;
        }
        return "delivered".equalsIgnoreCase(ackType)
                || "received".equalsIgnoreCase(ackType)
                || "ack_receive".equalsIgnoreCase(ackType);
    }

    private static boolean isReadAck(String ackType) {
        if (ackType == null) {
            return false;
        }
        return "read".equalsIgnoreCase(ackType) || "ack_read".equalsIgnoreCase(ackType);
    }

    private void handleAckAdvanceCursor(ChannelHandlerContext ctx, WsEnvelope msg, long ackUserId, AckType ackType) {
        CompletableFuture.runAsync(() -> {
            MessageEntity messageEntity = findMessageByAck(ctx, msg, ackUserId);
            if (messageEntity == null || messageEntity.getId() == null || messageEntity.getChatType() == null) {
                return;
            }

            long msgId = messageEntity.getId();
            ChatType chatType = messageEntity.getChatType();
            if (chatType == ChatType.SINGLE) {
                Long singleChatId = messageEntity.getSingleChatId();
                if (singleChatId == null || singleChatId <= 0) {
                    return;
                }
                if (ackType == AckType.DELIVERED) {
                    singleChatMemberService.markDelivered(singleChatId, ackUserId, msgId);
                } else if (ackType == AckType.READ) {
                    singleChatMemberService.markRead(singleChatId, ackUserId, msgId);
                }

                Long senderUserId = messageEntity.getFromUserId();
                if (senderUserId != null && senderUserId > 0 && senderUserId != ackUserId) {
                    WsEnvelope ack = new WsEnvelope();
                    ack.type = "ACK";
                    ack.from = ackUserId;
                    ack.to = senderUserId;
                    ack.serverMsgId = messageEntity.getServerMsgId();
                    ack.ackType = ackType.getDesc();
                    ack.ts = Instant.now().toEpochMilli();
                    for (Channel ch : sessionRegistry.getChannels(senderUserId)) {
                        if (ch == null || !ch.isActive()) {
                            continue;
                        }
                        ch.eventLoop().execute(() -> write(ch, ack));
                    }
                }
                return;
            }
            if (chatType == ChatType.GROUP) {
                Long groupId = messageEntity.getGroupId();
                if (groupId == null || groupId <= 0) {
                    return;
                }
                if (ackType == AckType.DELIVERED) {
                    groupMemberMapper.markDelivered(groupId, ackUserId, msgId);
                } else if (ackType == AckType.READ) {
                    groupMemberMapper.markRead(groupId, ackUserId, msgId);
                }
            }
        }, dbExecutor).orTimeout(3, TimeUnit.SECONDS).exceptionally(e -> {
            log.error("handle ack failed: {}", e.toString());
            return null;
        });
    }

    private MessageEntity findMessageByAck(ChannelHandlerContext ctx, WsEnvelope msg, long ackUserId) {
        String serverMsgId = msg.getServerMsgId();
        if (serverMsgId == null || serverMsgId.isBlank()) {
            ctx.executor().execute(() -> writeError(ctx, "missing_server_msg_id", msg.getClientMsgId(), null));
            return null;
        }

        MessageEntity entity = null;
        try {
            long id = Long.parseLong(serverMsgId);
            entity = messageService.getById(id);
        } catch (NumberFormatException ignore) {
            // fallthrough
        }
        if (entity == null) {
            entity = messageService.getOne(new LambdaQueryWrapper<MessageEntity>()
                    .eq(MessageEntity::getServerMsgId, serverMsgId)
                    .last("limit 1"));
        }
        if (entity == null) {
            ctx.executor().execute(() -> writeError(ctx, "message_not_found", msg.getClientMsgId(), serverMsgId));
            return null;
        }

        if (entity.getChatType() == ChatType.SINGLE) {
            Long toUserId = entity.getToUserId();
            if (toUserId == null || toUserId != ackUserId) {
                ctx.executor().execute(() -> writeError(ctx, "ack_not_allowed", msg.getClientMsgId(), serverMsgId));
                return null;
            }
        }
        return entity;
    }

    private void handleAuth(ChannelHandlerContext ctx, WsEnvelope msg) {
        // 为什么不在 WS 握手（HTTP Upgrade）阶段就鉴权？
        // 2) 首包 AUTH 是 IM 场景很常见的做法：协议统一、实现简单、也方便后续做重连补偿
        // 3) 面试时也更好讲清楚：连接建立 != 已鉴权，必须 AUTH 才能发业务消息
        // 兼容旧客户端：如果已经在握手阶段鉴权过，直接返回 AUTH_OK。
        if (sessionRegistry.isAuthed(ctx.channel())) {
            Long uid = ctx.channel().attr(SessionRegistry.ATTR_USER_ID).get();
            writeAuthOk(ctx, uid == null ? -1 : uid);
            if (uid != null) {
                resendPendingMessages(ctx, uid);
            }
            return;
        }

        if (msg.token == null || msg.token.isBlank()) {
            writeAuthFail(ctx, "missing_token");
            ctx.close();
            return;
        }
        Long userId;
        try {
            Jws<Claims> jws = jwtService.parseAccessToken(msg.token);
             userId = jwtService.getUserId(jws.getPayload());
            Long expMs = jws.getPayload().getExpiration() == null ? null : jws.getPayload().getExpiration().getTime();

            sessionRegistry.bind(ctx.channel(), userId, expMs);
            writeAuthOk(ctx, userId);
        } catch (Exception e) {
            writeAuthFail(ctx, "invalid_token");
            ctx.close();
            return;
        }

        resendPendingMessages(ctx, userId);




    }

    /**
     * reauth：在连接不断开的情况下刷新 accessToken 过期时间。
     *
     * <p>约束：</p>
     * <ul>
     *   <li>必须已绑定 userId（握手鉴权或 AUTH 已完成）</li>
     *   <li>新 token 的 uid 必须与当前连接一致</li>
     *   <li>成功后仅刷新 expMs，不触发离线补发</li>
     * </ul>
     */
    private void handleReauth(ChannelHandlerContext ctx, WsEnvelope msg) {
        Channel ch = ctx.channel();
        Long boundUid = ch.attr(SessionRegistry.ATTR_USER_ID).get();
        if (boundUid == null) {
            writeError(ctx, "unauthorized", msg.getClientMsgId(), msg.getServerMsgId());
            ctx.close();
            return;
        }
        if (msg.token == null || msg.token.isBlank()) {
            writeAuthFail(ctx, "missing_token");
            ctx.close();
            return;
        }

        try {
            Jws<Claims> jws = jwtService.parseAccessToken(msg.token);
            long uid = jwtService.getUserId(jws.getPayload());
            if (!boundUid.equals(uid)) {
                writeError(ctx, "reauth_uid_mismatch", msg.getClientMsgId(), msg.getServerMsgId());
                ctx.close();
                return;
            }

            Long expMs = jws.getPayload().getExpiration() == null ? null : jws.getPayload().getExpiration().getTime();
            sessionRegistry.bind(ch, uid, expMs);
            writeAuthOk(ctx, uid);
        } catch (Exception e) {
            writeAuthFail(ctx, "invalid_token");
            ctx.close();
        }
    }

    private void resendPendingMessages(ChannelHandlerContext ctx, Long userId) {
        if (userId == null || userId <= 0) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            singleChatMemberService.ensureMembersForUser(userId);

            List<MessageEntity> singleList = messageMapper.selectPendingSingleChatMessagesForUser(userId, 200);
            List<MessageEntity> groupList = messageMapper.selectPendingGroupMessagesForUser(userId, 200);

            Channel target = ctx.channel();
            if (target == null || !target.isActive()) {
                return;
            }

            for (MessageEntity msgEntity : singleList) {
                writePending(target, userId, msgEntity, false);
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
                    } catch (Exception ignore) {
                        // ignore
                    }
                }
            }
            for (MessageEntity msgEntity : groupList) {
                boolean important = msgEntity != null && msgEntity.getId() != null && importantMsgIds.contains(msgEntity.getId());
                writePending(target, userId, msgEntity, important);
            }
        }, dbExecutor).orTimeout(3, TimeUnit.SECONDS).exceptionally(e -> {
            log.error("resend pending messages failed: {}", e.toString());
            return null;
        });
    }

    private void writePending(Channel target, long userId, MessageEntity msgEntity, boolean important) {
        if (msgEntity == null) {
            return;
        }
        WsEnvelope envelope = new WsEnvelope();
        envelope.setType(msgEntity.getChatType() == null ? null : msgEntity.getChatType().getDesc());
        envelope.setFrom(msgEntity.getFromUserId());
        envelope.setClientMsgId(msgEntity.getClientMsgId());
        envelope.setTo(userId);
        envelope.setGroupId(msgEntity.getGroupId());
        envelope.setServerMsgId(msgEntity.getServerMsgId());
        envelope.setBody(msgEntity.getContent());
        envelope.setMsgType(msgEntity.getMsgType() == null ? null : msgEntity.getMsgType().getDesc());
        envelope.setTs(toEpochMilli(msgEntity.getCreatedAt()));
        if (important) {
            envelope.setImportant(true);
        }

        target.eventLoop().execute(() -> {
            ChannelFuture write = write(target, envelope);
            write.addListener(w -> {
                if (!w.isSuccess()) {
                    log.error("write error:{}", w.cause().toString());
                }
            });
        });
    }

    private static long toEpochMilli(LocalDateTime time) {
        if (time == null) {
            return Instant.now().toEpochMilli();
        }
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private ChannelFuture handlePing(ChannelHandlerContext ctx) {
        if (!sessionRegistry.isAuthed(ctx.channel())) {
            ChannelFuture f = writeError(ctx, "unauthorized", null, null);
            ctx.close();
            return f;
        }
        if (isExpired(ctx)) {
            ChannelFuture f = writeError(ctx, "token_expired", null, null);
            ctx.close();
            return f;
        }
        sessionRegistry.touch(ctx.channel());
        WsEnvelope pong = new WsEnvelope();
        pong.type = "PONG";
        pong.ts = Instant.now().toEpochMilli();
        return write(ctx, pong);
    }
    private void Ping(ChannelHandlerContext ctx){
        WsEnvelope ping = new WsEnvelope();
        ping.type = "PING";
        ping.ts = Instant.now().toEpochMilli();
        write(ctx, ping);
    }

    private boolean isExpired(ChannelHandlerContext ctx) {
        Long expMs = sessionRegistry.getAccessExpMs(ctx.channel());
        return expMs != null && Instant.now().toEpochMilli() >= expMs;
    }

    private ChannelFuture writeAuthOk(ChannelHandlerContext ctx, long userId) {
        WsEnvelope ok = new WsEnvelope();
        ok.type = "AUTH_OK";
        ok.from = userId;
        ok.ts = Instant.now().toEpochMilli();
        return write(ctx, ok);
    }

    private ChannelFuture writeAuthFail(ChannelHandlerContext ctx, String reason) {
        WsEnvelope fail = new WsEnvelope();
        fail.type = "AUTH_FAIL";
        fail.reason = reason;
        fail.ts = Instant.now().toEpochMilli();
        return write(ctx, fail);
    }

    private ChannelFuture writeError(ChannelHandlerContext ctx, String reason) {
        return writeError(ctx, reason, null, null);
    }

    private ChannelFuture writeError(ChannelHandlerContext ctx, String reason, String msgId, String serverMsgId) {
        WsEnvelope err = new WsEnvelope();
        err.type = "ERROR";
        err.clientMsgId = msgId;
        err.serverMsgId = serverMsgId;
        err.reason = reason;
        err.ts = Instant.now().toEpochMilli();
        return write(ctx, err);
    }

    private ChannelFuture writeAck(ChannelHandlerContext ctx, long fromUserId, String msgId, String serverMsgId, String ackType) {
        WsEnvelope ack = new WsEnvelope();
        ack.type = "ACK";
        ack.from = fromUserId;
        ack.clientMsgId = msgId;
        ack.serverMsgId = serverMsgId;
        ack.ackType = ackType;
        ack.ts = Instant.now().toEpochMilli();
        return write(ctx, ack);
    }

    private boolean validateSingleChat(ChannelHandlerContext ctx, WsEnvelope msg) {
        if (msg.getClientMsgId() == null || msg.getClientMsgId().isBlank()) {
            writeError(ctx, "missing_msg_id", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getTo() == null) {
            writeError(ctx, "missing_to", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getBody() == null || msg.getBody().isBlank()) {
            writeError(ctx, "missing_body", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getBody().length() > MAX_BODY_LEN) {
            writeError(ctx, "body_too_long", msg.getClientMsgId(), null);
            return false;
        }
        return true;
    }

    private boolean validateGroupChat(ChannelHandlerContext ctx, WsEnvelope msg) {
        if (msg.getClientMsgId() == null || msg.getClientMsgId().isBlank()) {
            writeError(ctx, "missing_msg_id", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getGroupId() == null || msg.getGroupId() <= 0) {
            writeError(ctx, "missing_group_id", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getBody() == null || msg.getBody().isBlank()) {
            writeError(ctx, "missing_body", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getBody().length() > MAX_BODY_LEN) {
            writeError(ctx, "body_too_long", msg.getClientMsgId(), null);
            return false;
        }
        return true;
    }

    private boolean validateAck(ChannelHandlerContext ctx, WsEnvelope msg) {
        if (msg.getTo() == null) {
            writeError(ctx, "missing_to", msg.getClientMsgId(), null);
            return false;
        }
        if (msg.getAckType() == null || msg.getAckType().isBlank()) {
            writeError(ctx, "missing_ack_type", msg.getClientMsgId(), null);
            return false;
        }
        String ackType = msg.getAckType();
        if ((msg.getClientMsgId() == null || msg.getClientMsgId().isBlank())
                && !(isDeliveredAck(ackType) || isReadAck(ackType))) {
            writeError(ctx, "missing_msg_id", msg.getClientMsgId(), null);
            return false;
        }
        if ((isDeliveredAck(ackType) || isReadAck(ackType))
                && (msg.getServerMsgId() == null || msg.getServerMsgId().isBlank())) {
            writeError(ctx, "missing_server_msg_id", msg.getClientMsgId(), null);
            return false;
        }
        return true;
    }

    private ChannelFuture write(ChannelHandlerContext ctx, WsEnvelope env) {
        try {
            String json = objectMapper.writeValueAsString(env);
            return ctx.writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception e) {
            log.warn("serialize ws message failed: {}", e.toString());
            return ctx.channel().newFailedFuture(e);
        }
    }

    private ChannelFuture write(Channel ch, WsEnvelope env) {
        String json;
        try {
            json = objectMapper.writeValueAsString(env);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return ch.writeAndFlush(new TextWebSocketFrame(json));
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent e && e.state() == IdleState.READER_IDLE) {
            // READER_IDLE：一段时间没有收到任何数据。
            // 这通常意味着：
            // - 客户端断网了但 TCP 没及时感知
            // - 客户端异常退出
            // - 客户端没按约定发心跳
            // 我们的处理策略：解绑会话并关闭连接。
            sessionRegistry.unbind(ctx.channel());
            ctx.close();
        }
        if (evt instanceof IdleStateEvent e && e.state() == IdleState.WRITER_IDLE) {
            // 在线状态依赖 Redis routeKey TTL；如果客户端长期不发 PING，TTL 会过期导致“在线/离线抖动”。
            // 这里在服务端心跳写出时也刷新 TTL，避免仅靠客户端心跳。
            if (sessionRegistry.isAuthed(ctx.channel()) && !isExpired(ctx)) {
                sessionRegistry.touch(ctx.channel());
            }
            Ping(ctx);
            //再次检测
//               sessionRegistry.unbind(ctx.channel());
//                ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Long userId = ctx.channel().attr(SessionRegistry.ATTR_USER_ID).get();
        if (userId != null) {
            CallRegistry.CallSession session = callRegistry.clearByUser(userId);
            if (session != null) {
                long peer = session.peerOf(userId);
                long callId = session.getCallId();
                LocalDateTime now = LocalDateTime.now();
                Integer durationSeconds = null;
                if (session.getAcceptedAtMs() != null) {
                    long d = Math.max(0, (Instant.now().toEpochMilli() - session.getAcceptedAtMs()) / 1000);
                    durationSeconds = (int) Math.min(d, Integer.MAX_VALUE);
                }
                final Integer durationSecondsFinal = durationSeconds;
                CompletableFuture.runAsync(() -> {
                    LambdaUpdateWrapper<CallRecordEntity> w = new LambdaUpdateWrapper<CallRecordEntity>()
                            .eq(CallRecordEntity::getCallId, callId)
                            .set(CallRecordEntity::getStatus, CallStatus.FAILED)
                            .set(CallRecordEntity::getFailReason, "peer_disconnect")
                            .set(CallRecordEntity::getEndedAt, now);
                    if (durationSecondsFinal != null) {
                        w.set(CallRecordEntity::getDurationSeconds, durationSecondsFinal);
                    }
                    callRecordService.update(w);
                }, dbExecutor);

                WsEnvelope out = new WsEnvelope();
                out.setType("CALL_END");
                out.setFrom(userId);
                out.setTo(peer);
                out.setCallId(callId);
                out.setCallReason("peer_disconnect");
                out.setTs(Instant.now().toEpochMilli());
                forwardToUser(peer, out);
            }
        }
        sessionRegistry.unbind(ctx.channel());
    }
    public void channelActive(ChannelHandlerContext ctx) {
        log.info(ctx.channel() + " connected");

    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        sessionRegistry.unbind(ctx.channel());
        ctx.close();
    }
}


