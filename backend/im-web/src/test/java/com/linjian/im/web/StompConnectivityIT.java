package com.linjian.im.web;

import com.linjian.im.security.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.WebSocketHttpHeaders;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W6 的双向连通验收：走真实的 WebSocket + STOMP 协议栈。
 *
 * <p>token 由 {@link JwtService} 直接签发，跳过登录接口——这样红灯的含义是唯一的：
 * 连接层或鉴权层出了问题，而不是「容器没起来」或「库表没初始化」。</p>
 *
 * <p>关于数据库：本测试不验证任何落库逻辑，但 W9 引入的 {@code AccountStatusChannelInterceptor}
 * 会在 CONNECT 帧阶段查 {@code im_user} 校验账号状态（被禁用 / 不存在直接关连接）。
 * 因此尽管初衷是「不依赖中间件」，这里仍须在测试库里种入被测账号这一行，否则会被误判为禁用而连接被拒。
 * 种入的是最小可用行，密码哈希是占位符——本测试不验证登录，只验证连接层与鉴权层。</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "im.jwt.secret=test-only-im-jwt-secret-for-integration-0123456789abcdefghijk",
                "im.jwt.expire-hours=24",
                // 仍保留较短的 Hikari 连接超时，避免上下文启动阶段被数据库拖住。
                "spring.datasource.hikari.connection-timeout=1000"
        })
@ActiveProfiles("test")
class StompConnectivityIT {

    /** 被测账号：token 与 CONNECT 鉴权都围绕这个 ID 展开，必须在测试库中存在且状态正常。 */
    private static final long TEST_USER_ID = 109859320094527488L;
    private static final String SECRET_IN_TEST = "test-only-im-jwt-secret-for-integration-0123456789abcdefghijk";
    private static final String OTHER_SECRET = "forged-secret-not-the-server-one-0123456789abcdefghijklmn";

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    private WebSocketStompClient client;
    private StompSession session;

    @BeforeEach
    void setUp() {
        // W9 的 AccountStatusChannelInterceptor 在 CONNECT 阶段查 im_user，
        // 被测账号必须存在且状态正常，否则会被当成「不存在 / 禁用」直接关连接。
        jdbc.update("INSERT IGNORE INTO im_user (id, username, password_hash, nickname, status) VALUES (?, ?, ?, ?, 1)",
                TEST_USER_ID, "linjian",
                "$2a$10$placeholderhashnotusedinthesetests0000000000000000000000", "林间");
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @AfterEach
    void tearDown() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
        if (client != null) {
            client.stop();
        }
    }

    private String url(String token) {
        return "ws://localhost:" + port + "/ws?token=" + token;
    }

    private StompHeaders connectHeaders(String token) {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        return headers;
    }

    @Test
    @DisplayName("情形一：合法 token 连接成功，ping 收到回显且回显里的 userId 与 token 一致")
    @Timeout(30)
    void validToken_receivesPongWithOwnUserId() throws Exception {
        long expectedUserId = TEST_USER_ID;
        String token = jwtService.issue(expectedUserId, "linjian");

        session = client.connectAsync(url(token), new WebSocketHttpHeaders(), connectHeaders(token),
                new StompSessionHandlerAdapter() {
                }).get(10, TimeUnit.SECONDS);

        CompletableFuture<Map<String, Object>> received = new CompletableFuture<>();
        session.subscribe("/user/queue/pong", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                received.complete((Map<String, Object>) payload);
            }
        });

        // 订阅帧与服务端登记之间存在极小的时间差，等一拍再发，避免偶发丢帧造成的假失败。
        Thread.sleep(300);
        session.send("/app/ping", Map.of("echo", "hello-w6"));

        Map<String, Object> pong = received.get(10, TimeUnit.SECONDS);

        assertThat(pong).containsEntry("type", "pong");
        assertThat(pong).containsEntry("userId", String.valueOf(expectedUserId));
        assertThat(pong).containsEntry("echo", "hello-w6");
        assertThat(pong.get("serverTime")).isNotNull();
    }

    @Test
    @DisplayName("情形二：不带 token 的握手被拒绝，连接建立不起来")
    @Timeout(30)
    void missingToken_handshakeRejected() {
        assertThatThrownBy(() -> client
                .connectAsync("ws://localhost:" + port + "/ws",
                        new WebSocketHttpHeaders(),
                        new StompHeaders(),
                        new StompSessionHandlerAdapter() {
                        })
                .get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("情形三：握手用合法 token，但 CONNECT 用伪造 token —— 仍被拒绝")
    @Timeout(30)
    void forgedTokenAtConnect_rejectedEvenIfHandshakePassed() {
        String valid = jwtService.issue(TEST_USER_ID, "linjian");
        String forged = Jwts.builder()
                .subject("109859320094527488")
                .claim("username", "linjian")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(Keys.hmacShaKeyFor(OTHER_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        // 握手用 valid（可通过），CONNECT 帧用 forged（应被拒）。
        // 服务端在 CONNECT 阶段抛 MessagingException 后会回 ERROR 帧并关闭连接，
        // 客户端因此拿不到可用会话，表现为 ExecutionException 且根因是 ConnectionLostException。
        // 关键是要断言「被主动关闭」而不是「超时」——超时说明服务端没做处理，只是没回包。
        long start = System.nanoTime();
        assertThatThrownBy(() -> {
            StompSession s = client
                    .connectAsync(url(valid), new WebSocketHttpHeaders(), connectHeaders(forged),
                            new StompSessionHandlerAdapter() {
                            })
                    .get(10, TimeUnit.SECONDS);
            session = s;
        })
                .isInstanceOf(ExecutionException.class)
                .hasMessageContaining("Connection closed");

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs)
                .as("拒绝应在收到 CONNECT 帧后立即发生，耗时 %dms 过长说明是在等超时而非主动拒绝", elapsedMs)
                .isLessThan(3_000L);
    }

    @Test
    @DisplayName("情形四：连接保持 32 秒后心跳未断，仍可正常收发")
    @Timeout(60)
    void heartbeat_keepsConnectionAliveBeyondThirtySeconds() throws Exception {
        String token = jwtService.issue(TEST_USER_ID, "linjian");
        session = client.connectAsync(url(token), new WebSocketHttpHeaders(), connectHeaders(token),
                new StompSessionHandlerAdapter() {
                }).get(10, TimeUnit.SECONDS);

        assertThat(session.isConnected()).isTrue();
        Thread.sleep(32_000);

        assertThat(session.isConnected())
                .as("32 秒后连接应仍处于连接状态（心跳周期 10 秒，若心跳失效服务端会关闭空闲连接）")
                .isTrue();

        // 仅 isConnected 为真还不够——它可能只是客户端本地状态没更新。
        // 再发一次 ping 并要求收到回显，才算真的双向可用。
        CompletableFuture<Map<String, Object>> received = new CompletableFuture<>();
        session.subscribe("/user/queue/pong", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                received.complete((Map<String, Object>) payload);
            }
        });
        Thread.sleep(300);
        session.send("/app/ping", Map.of("echo", "after-32s"));

        Map<String, Object> pong = received.get(10, TimeUnit.SECONDS);
        assertThat(pong).containsEntry("echo", "after-32s");
    }
}
