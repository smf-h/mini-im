package com.miniim.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.miniim.domain.entity.GroupJoinRequestEntity;

import java.util.List;

public interface GroupJoinRequestService extends IService<GroupJoinRequestEntity> {

    Long requestJoinByCode(long fromUserId, String groupCode, String message);

    List<GroupJoinRequestEntity> listForGroup(long operatorId, long groupId, String status, long limit, Long lastId);

    GroupJoinRequestEntity decide(long operatorId, long requestId, String action);
}

