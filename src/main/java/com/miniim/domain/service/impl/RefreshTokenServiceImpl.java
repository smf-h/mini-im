package com.miniim.domain.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.miniim.domain.entity.RefreshTokenEntity;
import com.miniim.domain.mapper.RefreshTokenMapper;
import com.miniim.domain.service.RefreshTokenService;
import org.springframework.stereotype.Service;

@Service
public class RefreshTokenServiceImpl extends ServiceImpl<RefreshTokenMapper, RefreshTokenEntity> implements RefreshTokenService {
}
