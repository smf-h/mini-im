package com.miniim.auth.service;

import com.miniim.auth.config.AuthProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {

    public static final String CLAIM_USER_ID = "uid";
    public static final String CLAIM_TOKEN_TYPE = "typ";
    public static final String CLAIM_SESSION_VERSION = "sv";

    public static final String TOKEN_TYPE_ACCESS = "access";

    private final AuthProperties props;
    private final SecretKey key;

    public JwtService(AuthProperties props) {
        this.props = props;
        this.key = Keys.hmacShaKeyFor(props.jwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 accessToken。
     *
     * <p>accessToken 的职责：证明“你是谁（uid）”，以及“你是哪台设备（did，可选）”。</p>
     *
     * <p>为什么要 deviceId？</p>
     * <ul>
     *   <li>多端登录时可以区分不同设备</li>
     *   <li>后续做“踢下线/设备级撤销/消息已读游标按设备维护”等都更自然</li>
     * </ul>
     */
    public String issueAccessToken(long userId) {
        return issueAccessToken(userId, 0);
    }

    public String issueAccessToken(long userId, long sessionVersion) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(props.accessTokenTtlSeconds());

        return Jwts.builder()
                .issuer(props.issuer())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claims(Map.of(
                        CLAIM_USER_ID, userId,
                        CLAIM_TOKEN_TYPE, TOKEN_TYPE_ACCESS,
                        CLAIM_SESSION_VERSION, sessionVersion
                ))
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验 accessToken。
     *
     * <p>这里做了几层校验：</p>
     * <ul>
     *   <li>签名校验（HMAC key）</li>
     *   <li>issuer 校验（防止拿别的系统签发的 token 来用）</li>
     *   <li>typ 校验（防止把 refreshToken/别的 token 类型当 accessToken 用）</li>
     * </ul>
     */
    public Jws<Claims> parseAccessToken(String token) {
        JwtParser parser = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(props.issuer())
                .build();

        Jws<Claims> jws = parser.parseSignedClaims(token);
        Claims claims = jws.getPayload();
        String typ = claims.get(CLAIM_TOKEN_TYPE, String.class);
        if (!TOKEN_TYPE_ACCESS.equals(typ)) {
            throw new JwtException("token_type_not_access");
        }
        return jws;
    }

    public long getUserId(Claims claims) {
        Number uid = claims.get(CLAIM_USER_ID, Number.class);
        if (uid == null) {
            throw new JwtException("missing_uid");
        }
        return uid.longValue();
    }

    public long getSessionVersion(Claims claims) {
        Number sv = claims.get(CLAIM_SESSION_VERSION, Number.class);
        return sv == null ? 0 : sv.longValue();
    }

}
