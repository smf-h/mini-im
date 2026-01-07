package com.miniim.domain.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.miniim.auth.web.AuthContext;
import com.miniim.common.api.ApiCodes;
import com.miniim.common.api.Result;
import com.miniim.domain.dto.SingleChatConversationDto;
import com.miniim.domain.entity.MessageEntity;
import com.miniim.domain.entity.SingleChatEntity;
import com.miniim.domain.enums.MessageStatus;
import com.miniim.domain.mapper.MessageMapper;
import com.miniim.domain.mapper.SingleChatMemberMapper;
import com.miniim.domain.service.SingleChatMemberService;
import com.miniim.domain.service.SingleChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@RestController
@RequestMapping("/single-chat/conversation")
public class SingleChatConversationController {

    private final SingleChatService singleChatService;
    private final MessageMapper messageMapper;
    private final SingleChatMemberService singleChatMemberService;
    private final SingleChatMemberMapper singleChatMemberMapper;

    @GetMapping("/cursor")
    public Result<List<SingleChatConversationDto>> cursor(
            @RequestParam(defaultValue = "20") Long limit,
            @RequestParam(required = false) LocalDateTime lastUpdatedAt,
            @RequestParam(required = false) Long lastId
    ) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }

        int safeLimit = 20;
        if (limit != null) {
            long raw = limit;
            if (raw < 1) raw = 1;
            if (raw > 100) raw = 100;
            safeLimit = (int) raw;
        }

        if ((lastUpdatedAt == null) != (lastId == null)) {
            return Result.fail(ApiCodes.BAD_REQUEST, "cursor_need_lastUpdatedAt_and_lastId");
        }

        LambdaQueryWrapper<SingleChatEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.nested(w -> w.eq(SingleChatEntity::getUser1Id, userId)
                .or()
                .eq(SingleChatEntity::getUser2Id, userId));
        wrapper.orderByDesc(SingleChatEntity::getUpdatedAt).orderByDesc(SingleChatEntity::getId);
        if (lastUpdatedAt != null && lastId != null) {
            wrapper.and(w -> w.lt(SingleChatEntity::getUpdatedAt, lastUpdatedAt)
                    .or()
                    .eq(SingleChatEntity::getUpdatedAt, lastUpdatedAt).lt(SingleChatEntity::getId, lastId));
        }
        wrapper.last("limit " + safeLimit);

        List<SingleChatEntity> chats = singleChatService.list(wrapper);
        return Result.ok(toDtos(userId, chats));
    }

    @GetMapping("/list")
    public Result<Page<SingleChatConversationDto>> list(
            @RequestParam(defaultValue = "1") Long pageNo,
            @RequestParam(defaultValue = "20") Long pageSize
    ) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }

        long safePageNo = 1;
        if (pageNo != null && pageNo > 0) {
            safePageNo = pageNo;
        }

        long safePageSize = 20;
        if (pageSize != null) {
            long raw = pageSize;
            if (raw < 1) raw = 1;
            if (raw > 100) raw = 100;
            safePageSize = raw;
        }

        Page<SingleChatEntity> page = new Page<>(safePageNo, safePageSize);
        LambdaQueryWrapper<SingleChatEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.nested(w -> w.eq(SingleChatEntity::getUser1Id, userId)
                .or()
                .eq(SingleChatEntity::getUser2Id, userId));
        wrapper.orderByDesc(SingleChatEntity::getUpdatedAt).orderByDesc(SingleChatEntity::getId);

        Page<SingleChatEntity> chatPage = singleChatService.page(page, wrapper);
        List<SingleChatConversationDto> records = toDtos(userId, chatPage.getRecords());

        Page<SingleChatConversationDto> out = new Page<>(chatPage.getCurrent(), chatPage.getSize(), chatPage.getTotal());
        out.setRecords(records);
        return Result.ok(out);
    }

    private List<SingleChatConversationDto> toDtos(Long userId, List<SingleChatEntity> chats) {
        if (chats == null || chats.isEmpty()) {
            return List.of();
        }

        singleChatMemberService.ensureMembersForUser(userId);

        List<Long> ids = chats.stream().map(SingleChatEntity::getId).collect(Collectors.toList());
        List<MessageEntity> lastMessages = messageMapper.selectLastMessagesBySingleChatIds(ids);
        Map<Long, MessageEntity> byChatId = new HashMap<>();
        if (lastMessages != null) {
            for (MessageEntity m : lastMessages) {
                byChatId.put(m.getSingleChatId(), m);
            }
        }

        Map<Long, Long> unreadByChatId = new HashMap<>();
        List<Map<String, Object>> unreadRows = messageMapper.selectUnreadCountsForUser(ids, userId);
        if (unreadRows != null) {
            for (Map<String, Object> row : unreadRows) {
                if (row == null) {
                    continue;
                }
                Object k = row.get("singleChatId");
                Object v = row.get("unreadCount");
                if (k instanceof Number chatId && v instanceof Number cnt) {
                    unreadByChatId.put(chatId.longValue(), cnt.longValue());
                }
            }
        }

        Map<String, com.miniim.domain.entity.SingleChatMemberEntity> memberByChatAndUser = new HashMap<>();
        List<com.miniim.domain.entity.SingleChatMemberEntity> members = singleChatMemberMapper.selectBySingleChatIds(ids);
        if (members != null) {
            for (com.miniim.domain.entity.SingleChatMemberEntity m : members) {
                if (m == null || m.getSingleChatId() == null || m.getUserId() == null) {
                    continue;
                }
                memberByChatAndUser.put(m.getSingleChatId() + ":" + m.getUserId(), m);
            }
        }

        return chats.stream().map(chat -> {
            SingleChatConversationDto dto = new SingleChatConversationDto();
            dto.setSingleChatId(chat.getId());
            dto.setUpdatedAt(chat.getUpdatedAt());

            Long peerUserId = chat.getUser1Id().equals(userId) ? chat.getUser2Id() : chat.getUser1Id();
            dto.setPeerUserId(peerUserId);

            Long unread = unreadByChatId.get(chat.getId());
            dto.setUnreadCount(unread == null ? 0L : unread);

            com.miniim.domain.entity.SingleChatMemberEntity myMember = memberByChatAndUser.get(chat.getId() + ":" + userId);
            dto.setMyLastReadMsgId(myMember == null ? null : myMember.getLastReadMsgId());

            com.miniim.domain.entity.SingleChatMemberEntity peerMember = memberByChatAndUser.get(chat.getId() + ":" + peerUserId);
            dto.setPeerLastReadMsgId(peerMember == null ? null : peerMember.getLastReadMsgId());

            MessageEntity last = byChatId.get(chat.getId());
            if (last != null) {
                SingleChatConversationDto.LastMessageDto lm = new SingleChatConversationDto.LastMessageDto();
                lm.setServerMsgId(last.getServerMsgId());
                lm.setFromUserId(last.getFromUserId());
                lm.setToUserId(last.getToUserId());
                lm.setContent(last.getStatus() == MessageStatus.REVOKED ? MessageEntity.REVOKED_PLACEHOLDER : last.getContent());
                lm.setCreatedAt(last.getCreatedAt());
                dto.setLastMessage(lm);
            }
            return dto;
        }).collect(Collectors.toList());
    }
}
