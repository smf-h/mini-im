package com.miniim.domain.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.miniim.auth.web.AuthContext;
import com.miniim.common.api.ApiCodes;
import com.miniim.common.api.Result;
import com.miniim.domain.dto.SingleChatMemberStateDto;
import com.miniim.domain.entity.SingleChatMemberEntity;
import com.miniim.domain.service.SingleChatMemberService;
import com.miniim.domain.service.SingleChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/single-chat/member")
public class SingleChatMemberController {

    private final SingleChatService singleChatService;
    private final SingleChatMemberService singleChatMemberService;

    @GetMapping("/state")
    public Result<SingleChatMemberStateDto> state(@RequestParam Long peerUserId) {
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
            SingleChatMemberStateDto dto = new SingleChatMemberStateDto();
            dto.setPeerUserId(peerUserId);
            return Result.ok(dto);
        }

        singleChatMemberService.ensureMembers(singleChatId, userId, peerUserId);

        SingleChatMemberEntity my = singleChatMemberService.getOne(new LambdaQueryWrapper<SingleChatMemberEntity>()
                .eq(SingleChatMemberEntity::getSingleChatId, singleChatId)
                .eq(SingleChatMemberEntity::getUserId, userId)
                .last("limit 1"));
        SingleChatMemberEntity peer = singleChatMemberService.getOne(new LambdaQueryWrapper<SingleChatMemberEntity>()
                .eq(SingleChatMemberEntity::getSingleChatId, singleChatId)
                .eq(SingleChatMemberEntity::getUserId, peerUserId)
                .last("limit 1"));

        SingleChatMemberStateDto dto = new SingleChatMemberStateDto();
        dto.setSingleChatId(singleChatId);
        dto.setPeerUserId(peerUserId);
        dto.setMyLastReadMsgId(my == null ? null : my.getLastReadMsgId());
        dto.setPeerLastReadMsgId(peer == null ? null : peer.getLastReadMsgId());
        return Result.ok(dto);
    }
}

