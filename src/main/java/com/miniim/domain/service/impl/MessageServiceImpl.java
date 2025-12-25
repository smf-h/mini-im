package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.miniim.domain.entity.MessageEntity;
import com.miniim.domain.entity.SingleChatEntity;
import com.miniim.domain.mapper.MessageMapper;
import com.miniim.domain.service.MessageService;
import com.miniim.domain.service.SingleChatService;
import org.springframework.stereotype.Service;

@Service
public class MessageServiceImpl extends ServiceImpl<MessageMapper, MessageEntity> implements MessageService {

    private final SingleChatService singleChatService;

    public MessageServiceImpl(SingleChatService singleChatService) {
        this.singleChatService = singleChatService;
    }

    @Override
    public Long getOrCreateSingleChatId(Long fromUserId, Long toUserId) {
        Long u1 = Math.min(fromUserId, toUserId);
        Long u2 = Math.max(fromUserId, toUserId);
        return singleChatService.getOrCreateSingleChatId(u1, u2);
    }
}
