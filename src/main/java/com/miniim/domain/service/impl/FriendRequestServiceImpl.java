package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.miniim.domain.entity.FriendRelationEntity;
import com.miniim.domain.entity.FriendRequestEntity;
import com.miniim.domain.enums.FriendRequestStatus;
import com.miniim.domain.mapper.FriendRequestMapper;
import com.miniim.domain.service.FriendRequestService;
import com.miniim.domain.service.FriendRelationService;
import com.miniim.domain.service.SingleChatService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class FriendRequestServiceImpl extends ServiceImpl<FriendRequestMapper, FriendRequestEntity> implements FriendRequestService {

    private final FriendRelationService friendRelationService;
    private final SingleChatService singleChatService;

    public FriendRequestServiceImpl(FriendRelationService friendRelationService, SingleChatService singleChatService) {
        this.friendRelationService = friendRelationService;
        this.singleChatService = singleChatService;
    }

    @Override
    public List<FriendRequestEntity> cursorByUserId(Long userId, String box, Long limit, Long lastId) {
        int safeLimit = 10;
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

        LambdaQueryWrapper<FriendRequestEntity> wrapper = buildBoxWrapper(userId, box);
        wrapper.orderByDesc(FriendRequestEntity::getId);
        if (lastId != null) {
            wrapper.lt(FriendRequestEntity::getId, lastId);
        }
        wrapper.last("limit " + safeLimit);
        return this.list(wrapper);
    }

    @Override
    public Page<FriendRequestEntity> pageByUserId(Long userId, String box, Long pageNo, Long pageSize) {
        long safePageNo = 1;
        if (pageNo != null && pageNo > 0) {
            safePageNo = pageNo;
        }

        long safePageSize = 10;
        if (pageSize != null) {
            long raw = pageSize;
            if (raw < 1) {
                raw = 1;
            }
            if (raw > 100) {
                raw = 100;
            }
            safePageSize = raw;
        }

        Page<FriendRequestEntity> page = new Page<>(safePageNo, safePageSize);
        LambdaQueryWrapper<FriendRequestEntity> wrapper = buildBoxWrapper(userId, box);
        wrapper.orderByDesc(FriendRequestEntity::getId);
        return this.page(page, wrapper);
    }

    @Transactional
    @Override
    public Long decide(Long userId, Long requestId, String action) {
        if (userId == null) {
            throw new IllegalArgumentException("unauthorized");
        }
        if (requestId == null || requestId <= 0) {
            throw new IllegalArgumentException("bad_request_id");
        }
        String safeAction = action == null ? "" : action.trim().toLowerCase();
        if (!"accept".equals(safeAction) && !"reject".equals(safeAction)) {
            throw new IllegalArgumentException("bad_action");
        }

        FriendRequestEntity req = this.getById(requestId);
        if (req == null) {
            throw new IllegalArgumentException("not_found");
        }
        if (!userId.equals(req.getToUserId())) {
            throw new IllegalArgumentException("forbidden");
        }

        FriendRequestStatus status = req.getStatus();
        if (status == FriendRequestStatus.ACCEPTED) {
            if ("accept".equals(safeAction)) {
                Long user1Id = Math.min(req.getFromUserId(), req.getToUserId());
                Long user2Id = Math.max(req.getFromUserId(), req.getToUserId());
                return singleChatService.getOrCreateSingleChatId(user1Id, user2Id);
            }
            throw new IllegalArgumentException("already_accepted");
        }
        if (status != FriendRequestStatus.PENDING) {
            throw new IllegalArgumentException("not_pending");
        }

        FriendRequestStatus newStatus = "accept".equals(safeAction) ? FriendRequestStatus.ACCEPTED : FriendRequestStatus.REJECTED;
        boolean updated = this.update(new LambdaUpdateWrapper<FriendRequestEntity>()
                .eq(FriendRequestEntity::getId, requestId)
                .eq(FriendRequestEntity::getStatus, FriendRequestStatus.PENDING)
                .set(FriendRequestEntity::getStatus, newStatus)
                .set(FriendRequestEntity::getHandledAt, LocalDateTime.now()));
        if (!updated) {
            throw new IllegalArgumentException("not_pending");
        }

        if (!"accept".equals(safeAction)) {
            return null;
        }

        Long user1Id = Math.min(req.getFromUserId(), req.getToUserId());
        Long user2Id = Math.max(req.getFromUserId(), req.getToUserId());

        // 1) 好友关系（幂等：唯一键冲突则忽略）
        try {
            FriendRelationEntity rel = new FriendRelationEntity();
            rel.setUser1Id(user1Id);
            rel.setUser2Id(user2Id);
            friendRelationService.save(rel);
        } catch (DuplicateKeyException ignored) {
            // 幂等：并发/重复 accept 可能触发唯一键冲突，视为已建立好友关系
        }

        // 好友集合缓存：关系变更后主动失效（双方）
        friendRelationService.evictFriendIdSet(user1Id);
        friendRelationService.evictFriendIdSet(user2Id);

        // 2) 创建/获取单聊会话（幂等：内部会先查后插）
        return singleChatService.getOrCreateSingleChatId(user1Id, user2Id);
    }

    private static LambdaQueryWrapper<FriendRequestEntity> buildBoxWrapper(Long userId, String box) {
        String safeBox = box == null ? "all" : box.trim().toLowerCase();

        LambdaQueryWrapper<FriendRequestEntity> wrapper = new LambdaQueryWrapper<>();
        switch (safeBox) {
            case "inbox" -> wrapper.eq(FriendRequestEntity::getToUserId, userId);
            case "outbox" -> wrapper.eq(FriendRequestEntity::getFromUserId, userId);
            case "all" -> wrapper.nested(w -> w.eq(FriendRequestEntity::getToUserId, userId)
                    .or()
                    .eq(FriendRequestEntity::getFromUserId, userId));
            default -> throw new IllegalArgumentException("bad_box");
        }
        return wrapper;
    }
}
