package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.miniim.domain.cache.SingleChatPairCache;
import com.miniim.domain.entity.SingleChatEntity;
import com.miniim.domain.mapper.SingleChatMapper;
import com.miniim.domain.service.SingleChatService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class SingleChatServiceImpl extends ServiceImpl<SingleChatMapper, SingleChatEntity> implements SingleChatService {

    private final SingleChatPairCache pairCache;

    public SingleChatServiceImpl(SingleChatPairCache pairCache) {
        this.pairCache = pairCache;
    }

    @Override
    public Long getOrCreateSingleChatId(Long user1Id, Long user2Id) {
        long[] pair = normalizePair(user1Id, user2Id);
        long a = pair[0];
        long b = pair[1];
        if (a <= 0 || b <= 0 || a == b) {
            throw new IllegalArgumentException("bad_user_id");
        }

        Long cached = pairCache.get(a, b);
        if (cached != null && cached > 0) {
            return cached;
        }

        Long existed = findSingleChatId(a, b);
        if (existed != null && existed > 0) {
            pairCache.put(a, b, existed);
            return existed;
        }

        try {
            SingleChatEntity newChat = new SingleChatEntity();
            newChat.setUser1Id(a);
            newChat.setUser2Id(b);
            this.save(newChat);
            Long id = newChat.getId();
            if (id != null && id > 0) {
                pairCache.put(a, b, id);
            }
            return id;
        } catch (DataIntegrityViolationException e) {
            // 并发场景：另一请求已插入成功，回读 DB 并回填缓存。
            Long id = findSingleChatId(a, b);
            if (id != null && id > 0) {
                pairCache.put(a, b, id);
                return id;
            }
            throw e;
        }
    }

    @Override
    public Long findSingleChatId(Long user1Id, Long user2Id) {
        long[] pair = normalizePair(user1Id, user2Id);
        long a = pair[0];
        long b = pair[1];
        if (a <= 0 || b <= 0 || a == b) {
            return null;
        }
        LambdaQueryWrapper<SingleChatEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SingleChatEntity::getUser1Id, a)
                .eq(SingleChatEntity::getUser2Id, b);
        SingleChatEntity chat = this.getOne(queryWrapper);
        return chat == null ? null : chat.getId();
    }

    private static long[] normalizePair(Long u1, Long u2) {
        long a = u1 == null ? 0 : u1;
        long b = u2 == null ? 0 : u2;
        if (a <= 0 || b <= 0) {
            return new long[]{0, 0};
        }
        if (a <= b) {
            return new long[]{a, b};
        }
        return new long[]{b, a};
    }
}
