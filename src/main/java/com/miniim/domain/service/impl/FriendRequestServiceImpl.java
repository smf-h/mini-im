package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.miniim.domain.entity.FriendRequestEntity;
import com.miniim.domain.mapper.FriendRequestMapper;
import com.miniim.domain.service.FriendRequestService;
import org.springframework.stereotype.Service;

@Service
public class FriendRequestServiceImpl extends ServiceImpl<FriendRequestMapper, FriendRequestEntity> implements FriendRequestService {
}
