package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.miniim.domain.dto.CallRecordDto;
import com.miniim.domain.entity.CallRecordEntity;
import com.miniim.domain.mapper.CallRecordMapper;
import com.miniim.domain.service.CallRecordService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class CallRecordServiceImpl extends ServiceImpl<CallRecordMapper, CallRecordEntity> implements CallRecordService {

    @Override
    public List<CallRecordDto> cursorByUserId(long userId, long limit, Long lastId) {
        long safeLimit = Math.min(Math.max(limit, 1), 100);

        LambdaQueryWrapper<CallRecordEntity> wrapper = new LambdaQueryWrapper<CallRecordEntity>()
                .nested(w -> w.eq(CallRecordEntity::getCallerUserId, userId).or().eq(CallRecordEntity::getCalleeUserId, userId))
                .orderByDesc(CallRecordEntity::getId)
                .last("limit " + safeLimit);
        if (lastId != null && lastId > 0) {
            wrapper.lt(CallRecordEntity::getId, lastId);
        }
        List<CallRecordEntity> list = this.list(wrapper);
        return mapDtos(userId, list);
    }

    @Override
    public Page<CallRecordDto> pageByUserId(long userId, long pageNo, long pageSize) {
        long safePageNo = pageNo > 0 ? pageNo : 1;
        long safePageSize = Math.min(Math.max(pageSize, 1), 100);

        Page<CallRecordEntity> page = new Page<>(safePageNo, safePageSize);
        LambdaQueryWrapper<CallRecordEntity> wrapper = new LambdaQueryWrapper<CallRecordEntity>()
                .nested(w -> w.eq(CallRecordEntity::getCallerUserId, userId).or().eq(CallRecordEntity::getCalleeUserId, userId))
                .orderByDesc(CallRecordEntity::getId);

        Page<CallRecordEntity> p = this.page(page, wrapper);
        Page<CallRecordDto> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        out.setRecords(mapDtos(userId, p.getRecords()));
        return out;
    }

    private static List<CallRecordDto> mapDtos(long userId, List<CallRecordEntity> list) {
        List<CallRecordDto> out = new ArrayList<>();
        if (list == null) return out;
        for (CallRecordEntity e : list) {
            if (e == null) continue;
            CallRecordDto dto = new CallRecordDto();
            dto.setId(e.getId());
            dto.setCallId(e.getCallId());
            dto.setStatus(e.getStatus());
            dto.setFailReason(e.getFailReason());
            dto.setStartedAt(e.getStartedAt());
            dto.setAcceptedAt(e.getAcceptedAt());
            dto.setEndedAt(e.getEndedAt());
            dto.setDurationSeconds(e.getDurationSeconds());

            boolean outgoing = e.getCallerUserId() != null && e.getCallerUserId() == userId;
            dto.setDirection(outgoing ? "OUT" : "IN");
            Long peer = outgoing ? e.getCalleeUserId() : e.getCallerUserId();
            dto.setPeerUserId(peer);
            out.add(dto);
        }
        return out;
    }
}

