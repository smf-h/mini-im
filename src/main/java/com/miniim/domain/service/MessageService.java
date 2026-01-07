package com.miniim.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.miniim.domain.entity.MessageEntity;

import java.util.List;

public interface MessageService extends IService<MessageEntity> {
//    Long getOrCreateSingleChatId(Long fromUserId, Long toUserId);

    /**
     * 按 id 倒序的游标分页：返回 id < lastId 的下一页；lastId 为空表示从最新开始。
     */
    List<MessageEntity> cursorBySingleChatId(Long singleChatId, Long limit, Long lastId);

    /**
     * 按 id 倒序的游标分页：返回 id < lastId 的下一页；lastId 为空表示从最新开始。
     */
    List<MessageEntity> cursorByGroupId(Long groupId, Long limit, Long lastId);

    /**
     * 增量拉取：返回 id > sinceId 的消息（按 id 升序）。
     */
    List<MessageEntity> sinceByGroupId(Long groupId, Long limit, Long sinceId);

    /**
     * 普通分页：按 pageNo/pageSize 返回 Page 对象。
     */
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<MessageEntity> pageBySingleChatId(Long singleChatId, Long pageNo, Long pageSize);
}
