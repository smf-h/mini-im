package com.miniim.domain.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.miniim.auth.web.AuthContext;
import com.miniim.common.api.ApiCodes;
import com.miniim.common.api.Result;
import com.miniim.domain.dto.CallRecordDto;
import com.miniim.domain.service.CallRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/call/record")
public class CallRecordController {

    private final CallRecordService callRecordService;

    @GetMapping("/cursor")
    public Result<List<CallRecordDto>> cursor(@RequestParam(required = false) Long limit,
                                              @RequestParam(required = false) Long lastId) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        long safeLimit = limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
        return Result.ok(callRecordService.cursorByUserId(userId, safeLimit, lastId));
    }

    @GetMapping("/list")
    public Result<Page<CallRecordDto>> list(@RequestParam(required = false) Long pageNo,
                                            @RequestParam(required = false) Long pageSize) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return Result.fail(ApiCodes.UNAUTHORIZED, "unauthorized");
        }
        long safePageNo = pageNo == null ? 1 : Math.max(pageNo, 1);
        long safePageSize = pageSize == null ? 20 : Math.min(Math.max(pageSize, 1), 100);
        return Result.ok(callRecordService.pageByUserId(userId, safePageNo, safePageSize));
    }
}

