package com.miniim.domain.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.miniim.auth.web.AuthContext;
import com.miniim.common.api.ApiCodes;
import com.miniim.common.api.Result;
import com.miniim.domain.entity.GroupMemberEntity;
import com.miniim.domain.entity.MessageEntity;
import com.miniim.domain.service.GroupMemberService;
import com.miniim.domain.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/group/message")
public class GroupMessageController {

    private final GroupMemberService groupMemberService;
    private final MessageService messageService;

    /**
     * 按 msgSeq 倒序的游标分页：返回 msgSeq < lastSeq 的下一页；lastSeq 为空表示从最新开始。
     */
    @GetMapping("/cursor")
    public Result<List<MessageEntity>> cursor(
            @RequestParam Long groupId,
            @RequestParam(defaultValue = "20") Long limit,
            @RequestParam(required = false) Long lastSeq
    ) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        if (groupId == null || groupId <= 0) {
            return Result.fail(ApiCodes.BAD_REQUEST, "missing_group_id");
        }

        long cnt = groupMemberService.count(new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId)
                .eq(GroupMemberEntity::getUserId, userId));
        if (cnt <= 0) {
            return Result.fail(ApiCodes.FORBIDDEN, "not_group_member");
        }

        return Result.ok(messageService.cursorByGroupId(groupId, limit, lastSeq));
    }

    /**
     * 增量拉取：返回 msgSeq > sinceSeq 的消息（按 msgSeq 升序）。sinceSeq 为空表示从 0 开始。
     */
    @GetMapping("/since")
    public Result<List<MessageEntity>> since(
            @RequestParam Long groupId,
            @RequestParam(defaultValue = "50") Long limit,
            @RequestParam(required = false) Long sinceSeq
    ) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        if (groupId == null || groupId <= 0) {
            return Result.fail(ApiCodes.BAD_REQUEST, "missing_group_id");
        }

        long cnt = groupMemberService.count(new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId)
                .eq(GroupMemberEntity::getUserId, userId));
        if (cnt <= 0) {
            return Result.fail(ApiCodes.FORBIDDEN, "not_group_member");
        }

        return Result.ok(messageService.sinceByGroupId(groupId, limit, sinceSeq));
    }
}
