package com.miniim.domain.web;

import com.miniim.auth.web.AuthContext;
import com.miniim.common.api.ApiCodes;
import com.miniim.common.api.Result;
import com.miniim.domain.dto.single.*;
import com.miniim.domain.service.SingleChatAppService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/single-chat")
public class SingleChatController {

    private static final int MAX_BODY_LEN = 4096;

    private final SingleChatAppService appService;

    public SingleChatController(SingleChatAppService appService) {
        this.appService = appService;
    }

    @PostMapping("/send")
    public Result<SendMessageResponse> send(@Valid @RequestBody SendMessageRequest req){
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        if (req.getContent().length() > MAX_BODY_LEN) {
            return Result.fail(ApiCodes.BAD_REQUEST, "body_too_long");
        }
        SendMessageResponse resp = appService.sendText(userId, req.getToUserId(), req.getClientMsgId(), req.getContent());
        return Result.ok(resp);
    }

    @GetMapping("/history")
    public Result<HistoryResponse> history(@RequestParam("peerId") Long peerId,
                                           @RequestParam(value = "cursor", required = false) Long cursor,
                                           @RequestParam(value = "size", required = false) Integer size){
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        return Result.ok(appService.history(userId, peerId, cursor, size));
    }

    @GetMapping("/conversation")
    public Result<ConversationIdResponse> conversation(@RequestParam("peerId") Long peerId){
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        Long id = appService.getOrCreateSingleChatId(userId, peerId);
        return Result.ok(new ConversationIdResponse(id));
    }
}
