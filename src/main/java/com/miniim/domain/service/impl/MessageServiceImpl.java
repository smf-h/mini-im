package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.miniim.domain.entity.MessageEntity;
import com.miniim.domain.entity.SingleChatEntity;
import com.miniim.domain.mapper.MessageMapper;
import com.miniim.domain.service.MessageService;
import org.springframework.stereotype.Service;

@Service
public class MessageServiceImpl extends ServiceImpl<MessageMapper, MessageEntity> implements MessageService {


}
