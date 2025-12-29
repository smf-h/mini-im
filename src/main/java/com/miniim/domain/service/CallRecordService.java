package com.miniim.domain.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.miniim.domain.dto.CallRecordDto;
import com.miniim.domain.entity.CallRecordEntity;

import java.util.List;

public interface CallRecordService extends IService<CallRecordEntity> {

    List<CallRecordDto> cursorByUserId(long userId, long limit, Long lastId);

    Page<CallRecordDto> pageByUserId(long userId, long pageNo, long pageSize);
}

