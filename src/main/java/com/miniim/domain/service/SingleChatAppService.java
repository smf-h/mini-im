package com.miniim.domain.service;

import com.miniim.domain.dto.single.HistoryResponse;
import com.miniim.domain.dto.single.SendMessageResponse;

public interface SingleChatAppService {
    // 保存文本消息，尝试投递；返回 serverMsgId 与状态
    SendMessageResponse sendText(Long fromUserId, Long toUserId, String clientMsgId, String content);

    // 游标历史：返回 id<cursor 的最近 size 条
    HistoryResponse history(Long selfUserId, Long peerId, Long cursor, Integer size);

    // 获取或创建单聊会话
    Long getOrCreateSingleChatId(Long user1Id, Long user2Id);
}
