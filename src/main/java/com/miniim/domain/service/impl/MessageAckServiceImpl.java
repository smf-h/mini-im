package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.miniim.domain.entity.MessageAckEntity;
import com.miniim.domain.mapper.MessageAckMapper;
import com.miniim.domain.service.MessageAckService;
import org.springframework.stereotype.Service;

@Service
public class MessageAckServiceImpl extends ServiceImpl<MessageAckMapper, MessageAckEntity> implements MessageAckService {
}
