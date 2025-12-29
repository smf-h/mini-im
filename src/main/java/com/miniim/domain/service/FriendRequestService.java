package com.miniim.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.miniim.domain.entity.FriendRequestEntity;

import java.util.List;

public interface FriendRequestService extends IService<FriendRequestEntity> {

    /**
     * 按 id 倒序的游标分页：返回 id < lastId 的下一页；lastId 为空表示从最新开始。
     *
     * @param userId 当前用户
     * @param box    inbox=我收到(outgoing to me), outbox=我发出(from me), all=收+发
     */
    List<FriendRequestEntity> cursorByUserId(Long userId, String box, Long limit, Long lastId);

    /**
     * 普通分页：按 pageNo/pageSize 返回 Page 对象（包含 records/total 等）。
     *
     * @param userId 当前用户
     * @param box    inbox=我收到(outgoing to me), outbox=我发出(from me), all=收+发
     */
    Page<FriendRequestEntity> pageByUserId(Long userId, String box, Long pageNo, Long pageSize);

    /**
     * 处理好友申请：同意/拒绝。
     *
     * @param userId    当前用户（必须为 toUserId）
     * @param requestId 好友申请 id
     * @param action    accept/reject
     * @return accept 时返回创建/获取到的 singleChatId；reject 时返回 null
     */
    Long decide(Long userId, Long requestId, String action);
}
