package com.linjian.im.security;

import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CONNECT 帧鉴权拦截器的确定性单测。
 *
 * <p>为什么要有这一层单测：集成测试里验证「伪造 token 被拒」时，客户端只能观察到
 * 「连不上」，无法精确区分是握手被拒还是 CONNECT 被拒。这里直接构造 CONNECT 帧喂给拦截器，
 * 可以确定地断言拒绝发生在 CONNECT 阶段本身。</p>
 */
class StompAuthChannelInterceptorTest {

    private static final String SECRET = "w6-unit-test-secret-0123456789abcdefghijklmnopqrstuvwxyz012345";
    private static final String OTHER_SECRET = "another-secret-for-forgery-0123456789abcdefghijklmnopqrstuv";

    private JwtService jwtService;
    private StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        jwtService = new JwtService(properties);
        jwtService.init();
        interceptor = new StompAuthChannelInterceptor(jwtService);
    }

    private Message<byte[]> buildConnect(String authorizationHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("sess-1");
        if (authorizationHeader != null) {
            accessor.addNativeHeader("Authorization", authorizationHeader);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("合法 token 的 CONNECT 被放行，并绑定 Principal")
    void validToken_bindsPrincipal() {
        String token = jwtService.issue(109859320094527488L, "linjian");
        Message<?> result = interceptor.preSend(buildConnect("Bearer " + token), null);

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
        assertThat(accessor.getUser()).isNotNull();
        assertThat(accessor.getUser().getName()).isEqualTo("109859320094527488");
    }

    @Test
    @DisplayName("缺少 Authorization 头的 CONNECT 被拒绝")
    void missingHeader_rejected() {
        assertThatThrownBy(() -> interceptor.preSend(buildConnect(null), null))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("缺少 Authorization");
    }

    @Test
    @DisplayName("用别的密钥伪造的 token 在 CONNECT 阶段被拒绝")
    void forgedToken_rejectedAtConnect() {
        String forged = Jwts.builder()
                .subject("109859320094527488")
                .claim("username", "linjian")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(Keys.hmacShaKeyFor(OTHER_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> interceptor.preSend(buildConnect("Bearer " + forged), null))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("CONNECT 鉴权失败");
    }

    @Test
    @DisplayName("已过期的 token 在 CONNECT 阶段被拒绝")
    void expiredToken_rejectedAtConnect() {
        String expired = Jwts.builder()
                .subject("109859320094527488")
                .issuedAt(new Date(System.currentTimeMillis() - 7_200_000))
                .expiration(new Date(System.currentTimeMillis() - 3_600_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> interceptor.preSend(buildConnect("Bearer " + expired), null))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    @DisplayName("非 CONNECT 帧不做 token 校验，原样放行")
    void nonConnectFrame_passesThrough() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId("sess-1");
        accessor.setLeaveMutable(true);
        Message<byte[]> send = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatCode(() -> assertThat(interceptor.preSend(send, null)).isSameAs(send))
                .doesNotThrowAnyException();
    }
}
