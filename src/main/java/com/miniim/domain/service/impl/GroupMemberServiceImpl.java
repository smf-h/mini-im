package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.miniim.domain.entity.GroupMemberEntity;
import com.miniim.domain.mapper.GroupMemberMapper;
import com.miniim.domain.service.GroupMemberService;
import org.springframework.stereotype.Service;

@Service
public class GroupMemberServiceImpl extends ServiceImpl<GroupMemberMapper, GroupMemberEntity> implements GroupMemberService {
}
