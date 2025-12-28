package com.miniim.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.miniim.domain.entity.SingleChatMemberEntity;

/**
 * 单聊成员游标（送达/已读）服务。
 *
 * <p>方案B：把“送达/已读/补发”从 t_message.status 迁移到成员维度，避免群聊无法表达多用户状态。</p>
 */
public interface SingleChatMemberService extends IService<SingleChatMemberEntity> {

    void ensureMember(long singleChatId, long userId);

    void ensureMembers(long singleChatId, long user1Id, long user2Id);

    /**
     * 兼容历史数据：扫描 t_single_chat，把缺失的 member 行补齐。
     */
    void ensureMembersForUser(long userId);

    void markDelivered(long singleChatId, long userId, long msgId);

    void markRead(long singleChatId, long userId, long msgId);
}

