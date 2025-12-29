package com.miniim.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.miniim.domain.entity.SingleChatEntity;

public interface SingleChatService extends IService<SingleChatEntity> {
    Long getOrCreateSingleChatId(Long user1Id, Long user2Id);

    /**
     * 查询单聊会话 id；不存在则返回 null。
     *
     * <p>注意：user1Id/user2Id 必须是 min/max 归一化后的值。</p>
     */
    Long findSingleChatId(Long user1Id, Long user2Id);
}
