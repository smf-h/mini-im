package com.miniim.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.miniim.domain.entity.SingleChatEntity;

public interface SingleChatService extends IService<SingleChatEntity> {
    Long getOrCreateSingleChatId(Long user1Id, Long user2Id);
}
