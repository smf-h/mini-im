package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.miniim.domain.entity.ConversationMemberEntity;
import com.miniim.domain.mapper.ConversationMemberMapper;
import com.miniim.domain.service.ConversationMemberService;
import org.springframework.stereotype.Service;

@Service
public class ConversationMemberServiceImpl extends ServiceImpl<ConversationMemberMapper, ConversationMemberEntity> implements ConversationMemberService {
}
