package com.yit.rag.common.util;

import com.yit.rag.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JWT 工具类（HS256）。
 *
 * <p>这个类解决什么问题：把「签发 token」和「校验并解析 token」收敛到一个地方，
 * 并且保证 token 里带了多租户 + RBAC 所需的全部信息，避免每次请求都查库：
 * <pre>
 *   userId / tenantId / tenantCode / username / roles / permissions
 * </pre>
 *
 * <p>取舍说明：把权限码放进 token 会带来「改权限后旧 token 仍生效」的问题。
 * 这里的做法是 token 有效期 12 小时（720 分钟）+ 前端可主动换 token；
 * 如果要求秒级生效，就应该只在 token 里放 userId，权限从 Redis 缓存读
 * （本项目已经准备好了 CacheService，扩展点很清晰）。
 */
@Slf4j
@Component
public class JwtUtil {

    public static final String CLAIM_USER_ID = "userId";
    public static final String CLAIM_TENANT_ID = "tenantId";
    public static final String CLAIM_TENANT_CODE = "tenantCode";
    public static final String CLAIM_USERNAME = "username";
    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_PERMISSIONS = "permissions";

    private final SecretKey secretKey;
    private final long expireMillis;

    public JwtUtil(AppProperties props) {
        String secret = props.getJwt().getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.jwt.secret 至少需要 32 个字符（HS256 要求 256bit）");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = props.getJwt().getExpireMinutes() * 60_000L;
        log.info("JWT 初始化完成，有效期 {} 分钟", props.getJwt().getExpireMinutes());
    }

    /**
     * 签发 token。
     */
    public String generate(Long userId, Long tenantId, String tenantCode, String username,
                           Set<String> roles, Set<String> permissions) {
        Date now = new Date();
        Map<String, Object> claims = new HashMap<>(8);
        claims.put(CLAIM_USER_ID, userId);
        claims.put(CLAIM_TENANT_ID, tenantId);
        claims.put(CLAIM_TENANT_CODE, tenantCode);
        claims.put(CLAIM_USERNAME, username);
        claims.put(CLAIM_ROLES, roles == null ? List.of() : List.copyOf(roles));
        claims.put(CLAIM_PERMISSIONS, permissions == null ? List.of() : List.copyOf(permissions));
        return Jwts.builder()
                .claims(claims)
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析并验签；失败返回 null（由调用方决定是 401 还是放行匿名）。
     */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 Claims 里取字符串列表（角色或权限）。
     */
    @SuppressWarnings("unchecked")
    public static Set<String> readStringSet(Claims claims, String key) {
        Object raw = claims.get(key);
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
        }
        return Set.of();
    }

    public long getExpireMillis() {
        return expireMillis;
    }
}
