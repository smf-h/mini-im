package com.miniim.domain.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.miniim.auth.web.AuthContext;
import com.miniim.common.api.ApiCodes;
import com.miniim.common.api.Result;
import com.miniim.domain.entity.MessageEntity;
import com.miniim.domain.service.MessageService;
import com.miniim.domain.service.SingleChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/single-chat/message")
public class SingleChatMessageController {

    private final SingleChatService singleChatService;
    private final MessageService messageService;

    /**
     * 按 msgSeq 倒序的游标分页：返回 msgSeq < lastSeq 的下一页；lastSeq 为空表示从最新开始。
     */
    @GetMapping("/cursor")
    public Result<List<MessageEntity>> cursor(
            @RequestParam Long peerUserId,
            @RequestParam(defaultValue = "20") Long limit,
            @RequestParam(required = false) Long lastSeq
    ) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        if (peerUserId == null || peerUserId <= 0) {
            return Result.fail(ApiCodes.BAD_REQUEST, "missing_peer_user_id");
        }
        if (peerUserId.equals(userId)) {
            return Result.fail(ApiCodes.BAD_REQUEST, "cannot_query_self_chat");
        }

        Long user1Id = Math.min(userId, peerUserId);
        Long user2Id = Math.max(userId, peerUserId);
        Long singleChatId = singleChatService.findSingleChatId(user1Id, user2Id);
        if (singleChatId == null) {
            return Result.ok(List.of());
        }
        return Result.ok(messageService.cursorBySingleChatId(singleChatId, limit, lastSeq));
    }

    /**
     * 普通分页：按 pageNo/pageSize 返回 Page 对象（包含 records/total 等）。
     */
    @GetMapping("/list")
    public Result<Page<MessageEntity>> list(
            @RequestParam Long peerUserId,
            @RequestParam(defaultValue = "1") Long pageNo,
            @RequestParam(defaultValue = "20") Long pageSize
    ) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        if (peerUserId == null || peerUserId <= 0) {
            return Result.fail(ApiCodes.BAD_REQUEST, "missing_peer_user_id");
        }
        if (peerUserId.equals(userId)) {
            return Result.fail(ApiCodes.BAD_REQUEST, "cannot_query_self_chat");
        }

        Long user1Id = Math.min(userId, peerUserId);
        Long user2Id = Math.max(userId, peerUserId);
        Long singleChatId = singleChatService.findSingleChatId(user1Id, user2Id);
        if (singleChatId == null) {
            return Result.ok(new Page<>(1, 0));
        }
        return Result.ok(messageService.pageBySingleChatId(singleChatId, pageNo, pageSize));
    }
}
