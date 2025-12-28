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
     * 按 id 倒序的游标分页：返回 id < lastId 的下一页；lastId 为空表示从最新开始。
     */
    @GetMapping("/cursor")
    public Result<List<MessageEntity>> cursor(
            @RequestParam Long groupId,
            @RequestParam(defaultValue = "20") Long limit,
            @RequestParam(required = false) Long lastId
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

        return Result.ok(messageService.cursorByGroupId(groupId, limit, lastId));
    }
}
