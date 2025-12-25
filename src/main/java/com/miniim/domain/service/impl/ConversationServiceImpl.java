package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.miniim.domain.entity.ConversationEntity;
import com.miniim.domain.entity.SingleChatEntity;
import com.miniim.domain.mapper.ConversationMapper;
import com.miniim.domain.service.ConversationService;
import org.springframework.stereotype.Service;

@Service
public class ConversationServiceImpl extends ServiceImpl<ConversationMapper, ConversationEntity> implements ConversationService {

}
