package com.linjian.im.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * token 签发与解析。
 *
 * <p>采用 HS384 而非 RS256：本项目是单体，签发方与验证方是同一个进程，
 * 非对称签名带来的「公钥可分发」优势用不上，反而多出一对密钥的管理成本。</p>
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtProperties properties;

    private SecretKey key;

    @PostConstruct
    void init() {
        // 密钥合法性已在 JwtProperties 中校验过，此处直接把字符串转成 HMAC 密钥。
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String issue(long userId, String username) {
        Instant now = Instant.now();
        Instant expiry = now.plus(Duration.ofHours(properties.getExpireHours()));
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                // 显式指定 HS384：若不指定，jjwt 会按密钥长度自动挑选算法，
                // 48 字节选 HS384、64 字节选 HS512。算法随密钥长度漂移会让「文档说 HS384、
                // 实际签出 HS512」这种不一致凭空出现，也会让排查签名问题时多一层变量。
                .signWith(key, Jwts.SIG.HS384)
                .compact();
    }

    /**
     * 解析并验签。
     *
     * @throws io.jsonwebtoken.JwtException 签名不合法、格式错误或已过期
     */
    public JwtUser parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        long userId = Long.parseLong(claims.getSubject());
        String username = claims.get("username", String.class);
        return new JwtUser(userId, username);
    }

    public long getExpireHours() {
        return properties.getExpireHours();
    }
}
