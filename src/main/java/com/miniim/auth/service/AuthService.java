package com.miniim.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.miniim.auth.config.AuthProperties;
import com.miniim.auth.dto.*;
import com.miniim.domain.service.CodeService;
import com.miniim.domain.entity.UserEntity;
import com.miniim.domain.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.time.Duration;
@Slf4j
@Service
public class AuthService {

    private final UserMapper userMapper;
    private final JwtService jwtService;
    private final TokenHasher tokenHasher;
    private final RefreshTokenStore refreshTokenStore;
    private final AuthProperties props;
    private final CodeService codeService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(
            UserMapper userMapper,
            JwtService jwtService,
            TokenHasher tokenHasher,
            RefreshTokenStore refreshTokenStore,
            AuthProperties props,
            CodeService codeService
    ) {
        this.userMapper = userMapper;
        this.jwtService = jwtService;
        this.tokenHasher = tokenHasher;
        this.refreshTokenStore = refreshTokenStore;
        this.props = props;
        this.codeService = codeService;
    }

    /**
     * 登录：校验用户名密码，签发 accessToken + refreshToken。
     *
     * <p>注意：refreshToken 不是 JWT（我们用随机串），并且服务端只保存它的 hash：</p>
     * <ul>
     *   <li>客户端拿到的是 raw refreshToken（明文随机串）</li>
     *   <li>服务端只存 sha256(raw) -> (userId, deviceId) 映射</li>
     * </ul>
     *
     * <p>这样做的好处：</p>
     * <ul>
     *   <li>即使 Redis 泄露，也拿不到可用的 refreshToken 明文</li>
     *   <li>便于“撤销 refreshToken”（直接删 key）</li>
     * </ul>
     */
    public LoginResponse login(LoginRequest request) {
        UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, request.username())
                .last("LIMIT 1"));

        // 兼容“首次登录自动注册”以及“历史数据 passwordHash 为空”的场景：
        // - user 不存在：直接插入新用户
        // - user 存在但 passwordHash 为空：补齐 passwordHash（更新同一行），避免重复插入导致 500
        if (user == null) {
            user = new UserEntity();
            user.setUsername(request.username());
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            if (userMapper.insert(user) != 1) {
                throw new IllegalArgumentException("register_failed");
            }
        } else if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            if (userMapper.updateById(user) != 1) {
                throw new IllegalArgumentException("register_failed");
            }
        } else if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("invalid_username_or_password");
        }

        // 确保 friendCode 已生成（用于“按码加好友”与个人主页展示）。
        try {
            codeService.ensureFriendCode(user.getId());
        } catch (Exception e) {
            log.warn("ensure friendCode failed: userId={}, err={}", user.getId(), e.toString());
        }

        String accessToken = jwtService.issueAccessToken(user.getId());
        String refreshToken = issueRefreshToken(user.getId());

        return new LoginResponse(
                user.getId(),
                accessToken,
                refreshToken,
                props.accessTokenTtlSeconds(),
                props.refreshTokenTtlSeconds()
        );
    }

    /**
     * 刷新：使用 refreshToken 换发新的 accessToken。
     *
     * <p>核心点：</p>
     * <ul>
     *   <li>refreshToken 本身是随机串；服务端通过 sha256 找到对应会话</li>
     *   <li>可选做“设备绑定”：同一个 refreshToken 只能在同一 deviceId 上使用</li>
     *   <li>可选做“滑动过期”：每次使用 refreshToken 都把 TTL 往后延</li>
     * </ul>
     */
    public RefreshResponse refresh(RefreshRequest request) {
        String tokenHash = tokenHasher.sha256Hex(request.refreshToken());

        RefreshTokenStore.RefreshSession session = refreshTokenStore.get(tokenHash);
        if (session == null) {
            throw new IllegalArgumentException("invalid_refresh_token");
        }

        // 可选：使用时延长 TTL（滑动过期）。不想滑动就删掉这一行。
        refreshTokenStore.touch(tokenHash, Duration.ofSeconds(props.refreshTokenTtlSeconds()));

        String accessToken = jwtService.issueAccessToken(session.userId());
        return new RefreshResponse(session.userId(), accessToken, props.accessTokenTtlSeconds());
    }

    public VerifyResponse verify(VerifyRequest request) {
        Jws<Claims> jws = jwtService.parseAccessToken(request.accessToken());
        Claims claims = jws.getPayload();
        long userId = jwtService.getUserId(claims);
        return VerifyResponse.ok(userId);
    }

    private String issueRefreshToken(long userId) {
        String raw = randomToken();
        String hash = tokenHasher.sha256Hex(raw);

        refreshTokenStore.put(hash, new RefreshTokenStore.RefreshSession(userId),
                Duration.ofSeconds(props.refreshTokenTtlSeconds()));
        return raw;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

}
