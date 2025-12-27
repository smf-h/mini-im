package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.miniim.domain.entity.MessageEntity;
import com.miniim.domain.mapper.MessageMapper;
import com.miniim.domain.service.MessageService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MessageServiceImpl extends ServiceImpl<MessageMapper, MessageEntity> implements MessageService {

    @Override
    public List<MessageEntity> cursorBySingleChatId(Long singleChatId, Long limit, Long lastId) {
        if (singleChatId == null || singleChatId <= 0) {
            return List.of();
        }

        int safeLimit = 20;
        if (limit != null) {
            long raw = limit;
            if (raw < 1) {
                raw = 1;
            }
            if (raw > 100) {
                raw = 100;
            }
            safeLimit = (int) raw;
        }

        LambdaQueryWrapper<MessageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MessageEntity::getSingleChatId, singleChatId);
        if (lastId != null) {
            wrapper.lt(MessageEntity::getId, lastId);
        }
        wrapper.orderByDesc(MessageEntity::getId);
        wrapper.last("limit " + safeLimit);
        return this.list(wrapper);
    }

    @Override
    public Page<MessageEntity> pageBySingleChatId(Long singleChatId, Long pageNo, Long pageSize) {
        if (singleChatId == null || singleChatId <= 0) {
            return new Page<>(1, 0);
        }

        long safePageNo = 1;
        if (pageNo != null) {
            safePageNo = Math.max(1, pageNo);
        }

        long safePageSize = 20;
        if (pageSize != null) {
            safePageSize = pageSize;
            if (safePageSize < 1) {
                safePageSize = 1;
            }
            if (safePageSize > 100) {
                safePageSize = 100;
            }
        }

        Page<MessageEntity> page = new Page<>(safePageNo, safePageSize);
        LambdaQueryWrapper<MessageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MessageEntity::getSingleChatId, singleChatId);
        wrapper.orderByDesc(MessageEntity::getId);
        return this.page(page, wrapper);
    }

}
