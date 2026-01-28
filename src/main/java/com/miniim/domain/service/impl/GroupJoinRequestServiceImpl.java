package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.miniim.domain.entity.GroupEntity;
import com.miniim.domain.entity.GroupJoinRequestEntity;
import com.miniim.domain.entity.GroupMemberEntity;
import com.miniim.domain.cache.GroupBaseCache;
import com.miniim.domain.cache.GroupMemberIdsCache;
import com.miniim.domain.enums.GroupJoinRequestStatus;
import com.miniim.domain.enums.MemberRole;
import com.miniim.domain.mapper.GroupJoinRequestMapper;
import com.miniim.domain.mapper.GroupMapper;
import com.miniim.domain.mapper.GroupMemberMapper;
import com.miniim.domain.service.GroupJoinRequestService;
import com.miniim.gateway.ws.WsEnvelope;
import com.miniim.gateway.ws.WsPushService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GroupJoinRequestServiceImpl extends ServiceImpl<GroupJoinRequestMapper, GroupJoinRequestEntity>
        implements GroupJoinRequestService {

    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final GroupBaseCache groupBaseCache;
    private final GroupMemberIdsCache groupMemberIdsCache;
    private final WsPushService wsPushService;

    @Transactional
    @Override
    public Long requestJoinByCode(long fromUserId, String groupCode, String message) {
        if (fromUserId <= 0) {
            throw new IllegalArgumentException("unauthorized");
        }
        String code = groupCode == null ? "" : groupCode.trim();
        code = code.toUpperCase();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("missing_group_code");
        }
        if (code.length() > 16) {
            throw new IllegalArgumentException("bad_group_code");
        }
        if (message != null && message.length() > 256) {
            throw new IllegalArgumentException("message_too_long");
        }

        GroupEntity g = groupMapper.selectOne(new LambdaQueryWrapper<GroupEntity>()
                .eq(GroupEntity::getGroupCode, code)
                .last("limit 1"));
        if (g == null || g.getId() == null) {
            throw new IllegalArgumentException("group_not_found");
        }

        long memberCnt = groupMemberMapper.selectCount(new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, g.getId())
                .eq(GroupMemberEntity::getUserId, fromUserId));
        if (memberCnt > 0) {
            throw new IllegalArgumentException("already_member");
        }

        GroupJoinRequestEntity existed = this.getOne(new LambdaQueryWrapper<GroupJoinRequestEntity>()
                .eq(GroupJoinRequestEntity::getGroupId, g.getId())
                .eq(GroupJoinRequestEntity::getFromUserId, fromUserId)
                .eq(GroupJoinRequestEntity::getStatus, GroupJoinRequestStatus.PENDING)
                .orderByDesc(GroupJoinRequestEntity::getId)
                .last("limit 1"));
        if (existed != null && existed.getId() != null) {
            return existed.getId();
        }

        GroupJoinRequestEntity req = new GroupJoinRequestEntity();
        req.setId(IdWorker.getId());
        req.setGroupId(g.getId());
        req.setFromUserId(fromUserId);
        req.setMessage(message);
        req.setStatus(GroupJoinRequestStatus.PENDING);
        this.save(req);

        // best-effort 通知群主/管理员：有人申请入群
        List<Long> adminIds = listOwnerAndAdmins(g.getId());
        if (!adminIds.isEmpty()) {
            WsEnvelope ev = new WsEnvelope();
            ev.type = "GROUP_JOIN_REQUEST";
            ev.groupId = g.getId();
            ev.from = fromUserId;
            ev.serverMsgId = String.valueOf(req.getId());
            ev.body = message;
            ev.ts = System.currentTimeMillis();
            wsPushService.pushToUsers(adminIds, ev);
        }

        return req.getId();
    }

    @Override
    public List<GroupJoinRequestEntity> listForGroup(long operatorId, long groupId, String status, long limit, Long lastId) {
        if (operatorId <= 0) {
            throw new IllegalArgumentException("unauthorized");
        }
        if (groupId <= 0) {
            throw new IllegalArgumentException("bad_group_id");
        }
        MemberRole role = getRole(groupId, operatorId);
        if (role == null || (role != MemberRole.OWNER && role != MemberRole.ADMIN)) {
            throw new IllegalArgumentException("forbidden");
        }

        GroupJoinRequestStatus st = GroupJoinRequestStatus.fromString(status);
        if (st == null) {
            st = GroupJoinRequestStatus.PENDING;
        }

        int safeLimit = (int) Math.max(1, Math.min(100, limit));
        LambdaQueryWrapper<GroupJoinRequestEntity> w = new LambdaQueryWrapper<GroupJoinRequestEntity>()
                .eq(GroupJoinRequestEntity::getGroupId, groupId)
                .eq(GroupJoinRequestEntity::getStatus, st)
                .orderByDesc(GroupJoinRequestEntity::getId)
                .last("limit " + safeLimit);
        if (lastId != null) {
            w.lt(GroupJoinRequestEntity::getId, lastId);
        }
        return this.list(w);
    }

    @Transactional
    @Override
    public GroupJoinRequestEntity decide(long operatorId, long requestId, String action) {
        if (operatorId <= 0) {
            throw new IllegalArgumentException("unauthorized");
        }
        if (requestId <= 0) {
            throw new IllegalArgumentException("bad_request_id");
        }
        String act = action == null ? "" : action.trim().toLowerCase();
        if (!"accept".equals(act) && !"reject".equals(act)) {
            throw new IllegalArgumentException("bad_action");
        }

        GroupJoinRequestEntity req = this.getById(requestId);
        if (req == null || req.getGroupId() == null) {
            throw new IllegalArgumentException("not_found");
        }

        MemberRole role = getRole(req.getGroupId(), operatorId);
        if (role == null || (role != MemberRole.OWNER && role != MemberRole.ADMIN)) {
            throw new IllegalArgumentException("forbidden");
        }

        if (req.getStatus() != GroupJoinRequestStatus.PENDING) {
            throw new IllegalArgumentException("not_pending");
        }

        GroupJoinRequestStatus next = "accept".equals(act) ? GroupJoinRequestStatus.ACCEPTED : GroupJoinRequestStatus.REJECTED;
        LocalDateTime now = LocalDateTime.now();
        boolean updated = this.update(new LambdaUpdateWrapper<GroupJoinRequestEntity>()
                .eq(GroupJoinRequestEntity::getId, requestId)
                .eq(GroupJoinRequestEntity::getStatus, GroupJoinRequestStatus.PENDING)
                .set(GroupJoinRequestEntity::getStatus, next)
                .set(GroupJoinRequestEntity::getHandledBy, operatorId)
                .set(GroupJoinRequestEntity::getHandledAt, now));
        if (!updated) {
            throw new IllegalArgumentException("not_pending");
        }

        if (next == GroupJoinRequestStatus.ACCEPTED) {
            long cnt = groupMemberMapper.selectCount(new LambdaQueryWrapper<GroupMemberEntity>()
                    .eq(GroupMemberEntity::getGroupId, req.getGroupId())
                    .eq(GroupMemberEntity::getUserId, req.getFromUserId()));
            if (cnt <= 0) {
                GroupMemberEntity m = new GroupMemberEntity();
                m.setId(IdWorker.getId());
                m.setGroupId(req.getGroupId());
                m.setUserId(req.getFromUserId());
                m.setRole(MemberRole.MEMBER);
                m.setJoinAt(now);
                m.setCreatedAt(now);
                m.setUpdatedAt(now);
                groupMemberMapper.insert(m);
            }
            groupBaseCache.evict(req.getGroupId());
            groupMemberIdsCache.evict(req.getGroupId());
        }

        GroupJoinRequestEntity out = this.getById(requestId);
        if (out != null && out.getFromUserId() != null) {
            WsEnvelope ev = new WsEnvelope();
            ev.type = "GROUP_JOIN_DECISION";
            ev.groupId = out.getGroupId();
            ev.from = operatorId;
            ev.to = out.getFromUserId();
            ev.serverMsgId = String.valueOf(out.getId());
            ev.body = out.getStatus() == GroupJoinRequestStatus.ACCEPTED ? "ACCEPTED" : "REJECTED";
            ev.ts = System.currentTimeMillis();
            wsPushService.pushToUser(out.getFromUserId(), ev);
        }
        return out;
    }

    private MemberRole getRole(long groupId, long userId) {
        GroupMemberEntity m = groupMemberMapper.selectOne(new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId)
                .eq(GroupMemberEntity::getUserId, userId)
                .last("limit 1"));
        return m == null ? null : m.getRole();
    }

    private List<Long> listOwnerAndAdmins(long groupId) {
        List<GroupMemberEntity> rows = groupMemberMapper.selectList(new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getGroupId, groupId)
                .in(GroupMemberEntity::getRole, MemberRole.OWNER, MemberRole.ADMIN));
        List<Long> out = new ArrayList<>();
        if (rows != null) {
            for (GroupMemberEntity m : rows) {
                if (m != null && m.getUserId() != null && m.getUserId() > 0) {
                    out.add(m.getUserId());
                }
            }
        }
        return out;
    }
}
