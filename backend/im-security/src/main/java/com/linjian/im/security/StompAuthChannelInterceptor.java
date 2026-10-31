package com.linjian.im.security;

import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 第二道闸：STOMP CONNECT 帧到达时再次验签，并把 Principal 绑定到本次会话。
 *
 * <p>为什么必须重复校验：握手拦截器只证明「建立 HTTP 连接的人手里有 token」，
 * 而 STOMP 是跑在这条连接之上的独立协议层；不校验 CONNECT 帧，就等于信任了
 * 「连接一建立，后续帧都可信」这个假设，而该假设在客户端被控时并不成立。</p>
 *
 * <p>校验失败时抛 {@link MessagingException}，Spring 会向客户端回一个 ERROR 帧并关闭会话，
 * 不会让该会话挂到任何 Principal 上。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTH_HEADER = "Authorization";

    private final JwtService jwtService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String header = accessor.getFirstNativeHeader(AUTH_HEADER);
        if (!StringUtils.hasText(header) || !header.startsWith(BEARER_PREFIX)) {
            throw new MessagingException("CONNECT 帧缺少 Authorization: Bearer <token> 头");
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        try {
            JwtUser user = jwtService.parse(token);
            accessor.setUser(new StompPrincipal(user.userId()));
            log.debug("CONNECT 鉴权通过 userId={} sessionId={}", user.userId(), accessor.getSessionId());
            return message;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("CONNECT 鉴权失败 sessionId={} 原因={}", accessor.getSessionId(), e.getMessage());
            throw new MessagingException("CONNECT 鉴权失败：" + e.getMessage(), e);
        }
    }
}
