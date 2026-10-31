package com.linjian.im.security;

import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * 第一道闸：HTTP 升级为 WebSocket 之前校验 token，不通过直接返回 401、拒绝升级。
 *
 * <p>令牌来源支持两种，按优先级：
 * 查询串 {@code ?token=xxx}（浏览器原生 WebSocket 无法自定义请求头，这是唯一可行的入口）、
 * 请求头 {@code Authorization: Bearer xxx}。</p>
 *
 * <p>注意这一层单独存在是不够的：客户端可以带着合法 token 完成握手，却用一个伪造的 token 去发
 * CONNECT 帧。因此还需要 {@link StompAuthChannelInterceptor} 做第二次校验。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HandshakeAuthInterceptor implements HandshakeInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String token = resolveToken(request);
        if (!StringUtils.hasText(token)) {
            return reject(response, "握手请求缺少 token");
        }
        try {
            JwtUser user = jwtService.parse(token);
            attributes.put(AuthAttributes.USER_ID, user.userId());
            attributes.put(AuthAttributes.USERNAME, user.username());
            log.debug("握手鉴权通过 userId={}", user.userId());
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return reject(response, "token 无效：" + e.getMessage());
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // 无需处理
    }

    private String resolveToken(ServerHttpRequest request) {
        String fromQuery = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .getFirst("token");
        if (StringUtils.hasText(fromQuery)) {
            return fromQuery;
        }
        String header = request.getHeaders().getFirst("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }

    private boolean reject(ServerHttpResponse response, String reason) {
        log.warn("握手被拒绝：{}", reason);
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }
}
