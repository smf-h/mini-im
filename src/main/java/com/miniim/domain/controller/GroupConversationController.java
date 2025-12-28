package com.miniim.domain.controller;

import com.miniim.auth.web.AuthContext;
import com.miniim.common.api.ApiCodes;
import com.miniim.common.api.Result;
import com.miniim.domain.dto.GroupConversationDto;
import com.miniim.domain.entity.GroupEntity;
import com.miniim.domain.entity.MessageEntity;
import com.miniim.domain.mapper.MessageMapper;
import com.miniim.domain.mapper.MessageMentionMapper;
import com.miniim.domain.service.GroupService;
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
@RequestMapping("/group/conversation")
public class GroupConversationController {

    private final GroupService groupService;
    private final MessageMapper messageMapper;
    private final MessageMentionMapper messageMentionMapper;

    @GetMapping("/cursor")
    public Result<List<GroupConversationDto>> cursor(
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

        List<GroupEntity> groups = groupService.cursorByUserId(userId, safeLimit, lastUpdatedAt, lastId);
        if (groups == null || groups.isEmpty()) {
            return Result.ok(List.of());
        }

        List<Long> groupIds = groups.stream().map(GroupEntity::getId).collect(Collectors.toList());

        Map<Long, MessageEntity> lastByGroupId = new HashMap<>();
        List<MessageEntity> lastMessages = messageMapper.selectLastMessagesByGroupIds(groupIds);
        if (lastMessages != null) {
            for (MessageEntity m : lastMessages) {
                if (m == null || m.getGroupId() == null) {
                    continue;
                }
                lastByGroupId.put(m.getGroupId(), m);
            }
        }

        Map<Long, Long> unreadByGroupId = new HashMap<>();
        List<Map<String, Object>> unreadRows = messageMapper.selectGroupUnreadCountsForUser(groupIds, userId);
        if (unreadRows != null) {
            for (Map<String, Object> row : unreadRows) {
                if (row == null) {
                    continue;
                }
                Object k = row.get("groupId");
                Object v = row.get("unreadCount");
                if (k instanceof Number gid && v instanceof Number cnt) {
                    unreadByGroupId.put(gid.longValue(), cnt.longValue());
                }
            }
        }

        Map<Long, Long> mentionUnreadByGroupId = new HashMap<>();
        List<Map<String, Object>> mentionRows = messageMentionMapper.selectMentionUnreadCountsForUser(groupIds, userId);
        if (mentionRows != null) {
            for (Map<String, Object> row : mentionRows) {
                if (row == null) {
                    continue;
                }
                Object k = row.get("groupId");
                Object v = row.get("mentionUnreadCount");
                if (k instanceof Number gid && v instanceof Number cnt) {
                    mentionUnreadByGroupId.put(gid.longValue(), cnt.longValue());
                }
            }
        }

        List<GroupConversationDto> out = groups.stream().map(g -> {
            GroupConversationDto dto = new GroupConversationDto();
            dto.setGroupId(g.getId());
            dto.setName(g.getName());
            dto.setUpdatedAt(g.getUpdatedAt());
            dto.setUnreadCount(unreadByGroupId.getOrDefault(g.getId(), 0L));
            dto.setMentionUnreadCount(mentionUnreadByGroupId.getOrDefault(g.getId(), 0L));

            MessageEntity last = lastByGroupId.get(g.getId());
            if (last != null) {
                GroupConversationDto.LastMessageDto lm = new GroupConversationDto.LastMessageDto();
                lm.setServerMsgId(last.getServerMsgId());
                lm.setFromUserId(last.getFromUserId());
                lm.setContent(last.getContent());
                lm.setCreatedAt(last.getCreatedAt());
                dto.setLastMessage(lm);
            }
            return dto;
        }).collect(Collectors.toList());

        return Result.ok(out);
    }
}

