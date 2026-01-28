package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.miniim.domain.entity.SingleChatEntity;
import com.miniim.domain.entity.SingleChatMemberEntity;
import com.miniim.domain.mapper.SingleChatMapper;
import com.miniim.domain.mapper.SingleChatMemberMapper;
import com.miniim.domain.service.SingleChatMemberService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SingleChatMemberServiceImpl extends ServiceImpl<SingleChatMemberMapper, SingleChatMemberEntity>
        implements SingleChatMemberService {

    private final SingleChatMapper singleChatMapper;

    public SingleChatMemberServiceImpl(SingleChatMapper singleChatMapper) {
        this.singleChatMapper = singleChatMapper;
    }

    @Override
    public void ensureMember(long singleChatId, long userId) {
        if (singleChatId <= 0 || userId <= 0) {
            return;
        }

        boolean exists = this.exists(new LambdaQueryWrapper<SingleChatMemberEntity>()
                .eq(SingleChatMemberEntity::getSingleChatId, singleChatId)
                .eq(SingleChatMemberEntity::getUserId, userId)
                .last("limit 1"));
        if (exists) {
            return;
        }

        SingleChatMemberEntity member = new SingleChatMemberEntity();
        member.setSingleChatId(singleChatId);
        member.setUserId(userId);
        try {
            this.save(member);
        } catch (DuplicateKeyException ignore) {
            // 幂等：并发/重复插入可能触发唯一键冲突，忽略即可
        }
    }

    @Override
    public void ensureMembers(long singleChatId, long user1Id, long user2Id) {
        ensureMember(singleChatId, user1Id);
        ensureMember(singleChatId, user2Id);
    }

    @Override
    public void ensureMembersForUser(long userId) {
        if (userId <= 0) {
            return;
        }
        List<SingleChatEntity> chats = singleChatMapper.selectList(new LambdaQueryWrapper<SingleChatEntity>()
                .and(w -> w.eq(SingleChatEntity::getUser1Id, userId).or().eq(SingleChatEntity::getUser2Id, userId)));
        for (SingleChatEntity chat : chats) {
            if (chat == null || chat.getId() == null) {
                continue;
            }
            Long user1Id = chat.getUser1Id();
            Long user2Id = chat.getUser2Id();
            if (user1Id != null && user2Id != null) {
                ensureMembers(chat.getId(), user1Id, user2Id);
            } else {
                ensureMember(chat.getId(), userId);
            }
        }
    }

    @Override
    public void markDelivered(long singleChatId, long userId, long msgSeq) {
        if (singleChatId <= 0 || userId <= 0 || msgSeq <= 0) {
            return;
        }

        int updated = this.getBaseMapper().markDeliveredSeq(singleChatId, userId, msgSeq);
        if (updated > 0) {
            return;
        }

        boolean exists = this.exists(new LambdaQueryWrapper<SingleChatMemberEntity>()
                .eq(SingleChatMemberEntity::getSingleChatId, singleChatId)
                .eq(SingleChatMemberEntity::getUserId, userId)
                .last("limit 1"));
        if (exists) {
            // MySQL：若 greatest(...) 结果不变，可能返回 0 rows affected；此时无需补建
            return;
        }

        ensureMember(singleChatId, userId);
        this.getBaseMapper().markDeliveredSeq(singleChatId, userId, msgSeq);
    }

    @Override
    public void markRead(long singleChatId, long userId, long msgSeq) {
        if (singleChatId <= 0 || userId <= 0 || msgSeq <= 0) {
            return;
        }

        int updated = this.getBaseMapper().markReadSeq(singleChatId, userId, msgSeq);
        if (updated > 0) {
            return;
        }

        boolean exists = this.exists(new LambdaQueryWrapper<SingleChatMemberEntity>()
                .eq(SingleChatMemberEntity::getSingleChatId, singleChatId)
                .eq(SingleChatMemberEntity::getUserId, userId)
                .last("limit 1"));
        if (exists) {
            return;
        }

        ensureMember(singleChatId, userId);
        this.getBaseMapper().markReadSeq(singleChatId, userId, msgSeq);
    }
}

