package com.miniim.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.miniim.domain.config.CodeProperties;
import com.miniim.domain.entity.GroupEntity;
import com.miniim.domain.entity.UserEntity;
import com.miniim.domain.mapper.GroupMapper;
import com.miniim.domain.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CodeService {

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int MAX_TRIES = 30;

    private final UserMapper userMapper;
    private final GroupMapper groupMapper;
    private final CodeProperties props;

    private final SecureRandom random = new SecureRandom();

    public long resetCooldownSeconds() {
        long v = props.getResetCooldownSeconds();
        return v <= 0 ? 86400 : v;
    }

    public String newUniqueFriendCode() {
        int len = Math.max(6, Math.min(16, props.getFriendCodeLength()));
        for (int i = 0; i < MAX_TRIES; i++) {
            String code = randomCode(len);
            long cnt = userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                    .eq(UserEntity::getFriendCode, code));
            if (cnt == 0) {
                return code;
            }
        }
        throw new IllegalStateException("friend_code_generation_failed");
    }

    public String newUniqueGroupCode() {
        int len = Math.max(6, Math.min(16, props.getGroupCodeLength()));
        for (int i = 0; i < MAX_TRIES; i++) {
            String code = randomCode(len);
            long cnt = groupMapper.selectCount(new LambdaQueryWrapper<GroupEntity>()
                    .eq(GroupEntity::getGroupCode, code));
            if (cnt == 0) {
                return code;
            }
        }
        throw new IllegalStateException("group_code_generation_failed");
    }

    public LocalDateTime nextResetAt(LocalDateTime lastUpdatedAt) {
        if (lastUpdatedAt == null) {
            return null;
        }
        return lastUpdatedAt.plusSeconds(resetCooldownSeconds());
    }

    public boolean canReset(LocalDateTime lastUpdatedAt, LocalDateTime now) {
        if (now == null) {
            now = LocalDateTime.now();
        }
        LocalDateTime next = nextResetAt(lastUpdatedAt);
        return next == null || !now.isBefore(next);
    }

    @Transactional
    public UserEntity ensureFriendCode(long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        if (user.getFriendCode() != null && !user.getFriendCode().isBlank()) {
            return user;
        }
        LocalDateTime now = LocalDateTime.now();
        String code = newUniqueFriendCode();
        userMapper.update(new LambdaUpdateWrapper<UserEntity>()
                .eq(UserEntity::getId, userId)
                .set(UserEntity::getFriendCode, code)
                .set(UserEntity::getFriendCodeUpdatedAt, now));
        user.setFriendCode(code);
        user.setFriendCodeUpdatedAt(now);
        return user;
    }

    @Transactional
    public String resetFriendCode(long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("not_found");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!canReset(user.getFriendCodeUpdatedAt(), now)) {
            throw new IllegalArgumentException("cooldown_not_reached");
        }
        String code = newUniqueFriendCode();
        boolean ok = userMapper.update(new LambdaUpdateWrapper<UserEntity>()
                .eq(UserEntity::getId, userId)
                .set(UserEntity::getFriendCode, code)
                .set(UserEntity::getFriendCodeUpdatedAt, now)) == 1;
        if (!ok) {
            throw new IllegalStateException("reset_failed");
        }
        return code;
    }

    @Transactional
    public GroupEntity ensureGroupCode(long groupId) {
        GroupEntity g = groupMapper.selectById(groupId);
        if (g == null) {
            return null;
        }
        if (g.getGroupCode() != null && !g.getGroupCode().isBlank()) {
            return g;
        }
        LocalDateTime now = LocalDateTime.now();
        String code = newUniqueGroupCode();
        groupMapper.update(new LambdaUpdateWrapper<GroupEntity>()
                .eq(GroupEntity::getId, groupId)
                .set(GroupEntity::getGroupCode, code)
                .set(GroupEntity::getGroupCodeUpdatedAt, now));
        g.setGroupCode(code);
        g.setGroupCodeUpdatedAt(now);
        return g;
    }

    @Transactional
    public String resetGroupCode(long groupId, LocalDateTime now) {
        GroupEntity g = groupMapper.selectById(groupId);
        if (g == null) {
            throw new IllegalArgumentException("not_found");
        }
        if (now == null) {
            now = LocalDateTime.now();
        }
        if (!canReset(g.getGroupCodeUpdatedAt(), now)) {
            throw new IllegalArgumentException("cooldown_not_reached");
        }
        String code = newUniqueGroupCode();
        boolean ok = groupMapper.update(new LambdaUpdateWrapper<GroupEntity>()
                .eq(GroupEntity::getId, groupId)
                .set(GroupEntity::getGroupCode, code)
                .set(GroupEntity::getGroupCodeUpdatedAt, now)) == 1;
        if (!ok) {
            throw new IllegalStateException("reset_failed");
        }
        return code;
    }

    private String randomCode(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}

