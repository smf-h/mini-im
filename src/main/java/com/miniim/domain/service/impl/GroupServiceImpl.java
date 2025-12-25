package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.miniim.domain.entity.GroupEntity;
import com.miniim.domain.mapper.GroupMapper;
import com.miniim.domain.service.GroupService;
import org.springframework.stereotype.Service;

@Service
public class GroupServiceImpl extends ServiceImpl<GroupMapper, GroupEntity> implements GroupService {
}
