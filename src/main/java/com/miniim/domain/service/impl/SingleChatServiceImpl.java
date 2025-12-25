package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.miniim.domain.entity.SingleChatEntity;
import com.miniim.domain.mapper.SingleChatMapper;
import com.miniim.domain.service.SingleChatService;
import org.springframework.stereotype.Service;

@Service
public class SingleChatServiceImpl extends ServiceImpl<SingleChatMapper, SingleChatEntity> implements SingleChatService {
    @Override
    public Long getOrCreateSingleChatId(Long user1Id, Long user2Id) {
        LambdaQueryWrapper<SingleChatEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SingleChatEntity::getUser1Id, user1Id)
                    .eq(SingleChatEntity::getUser2Id, user2Id);
        SingleChatEntity chat = this.getOne(queryWrapper);
        if (chat != null) {
            return chat.getId();
        }
        else {
            SingleChatEntity newChat = new SingleChatEntity();
            newChat.setUser1Id(user1Id);
            newChat.setUser2Id(user2Id);
            this.save(newChat);
            return newChat.getId();
        }
    }
}
