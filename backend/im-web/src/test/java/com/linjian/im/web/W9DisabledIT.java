package com.linjian.im.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.linjian.im.chat.service.SessionService;
import com.linjian.im.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「禁用」在实时链路上的生效边界。
 *
 * <p>这是 W9 最容易漏测的一条：REST 侧的 {@code AccountStatusFilter} 拦不住已建立的
 * WebSocket 连接（WS 不走 Servlet 过滤链）。如果只测 REST，会得到一个
 * 「禁用功能正常」的错误结论——实际上被禁用的人只要不断线就能一直发消息。</p>
 *
 * <p>三个断言对应三件事：已连接的人发不出消息、被禁用的 token 连不上、
 * 其他用户完全不受影响（这条是为了防止「修过头」，把所有人都拦掉）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class W9DisabledIT {

    private static final List<String> TABLES = List.of(
            "im_inbox", "im_read_cursor", "im_message", "im_session_member", "im_session", "im_user");

    private static final long USER_A = 4001L;
    private static final long USER_D = 4004L;

    @LocalServerPort
    private int port;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private SessionService sessionService;

    private WebSocketStompClient client;
    private StompSession sessionA;
    private StompSession sessionD;

    private String tokenA;
    private String tokenD;

    @BeforeEach
    void setUp() {
        TABLES.forEach(t -> jdbc.execute("TRUNCATE TABLE " + t));
        Set<String> keys = redis.keys("im:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }

        insertUser(USER_A, "alice", "爱丽丝");
        insertUser(USER_D, "dave", "戴夫");

        tokenA = jwtService.issue(USER_A, "alice");
        tokenD = jwtService.issue(USER_D, "dave");

        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @AfterEach
    void tearDown() {
        disconnect(sessionA);
        disconnect(sessionD);
        if (client != null) {
            client.stop();
        }
    }

    @Test
    @Timeout(40)
    @DisplayName("已连接的用户被禁用后，下一次发消息被拒，且连接随之关闭")
    void disablingClosesBehaviourOnLiveConnection() throws Exception {
        long sessionId = sessionService.openSingle(USER_A, USER_D);

        sessionD = connect(tokenD);
        LinkedBlockingQueue<JsonNode> acks = subscribe(sessionD, "/user/queue/ack");
        // 订阅帧本身也要通过状态闸，等它落地再改状态，避免把「订阅被拒」误判成「发送被拒」
        Thread.sleep(400);

        // 禁用前先确认这条路是通的——否则「禁用后收不到 ack」可能只是本来就收不到
        sessionD.send("/app/chat.send", Map.of(
                "sessionId", sessionId, "clientMsgId", "before-disable", "content", "禁用前"));
        assertThat(acks.poll(10, TimeUnit.SECONDS)).as("禁用前应能正常发送").isNotNull();

        jdbc.update("UPDATE im_user SET status = 0 WHERE id = ?", USER_D);

        sessionD.send("/app/chat.send", Map.of(
                "sessionId", sessionId, "clientMsgId", "after-disable", "content", "禁用后"));

        assertThat(acks.poll(3, TimeUnit.SECONDS))
                .as("被禁用后不应再收到 ack——收到就说明状态闸没生效").isNull();
        // Spring 收到拦截器抛出的 MessagingException 会回 ERROR 帧并关闭会话，等着它发生
        Thread.sleep(1500);
        assertThat(sessionD.isConnected())
                .as("被拒的帧会让连接关闭（这是 STOMP 规范行为，不是我们主动踢线）").isFalse();
    }

    @Test
    @Timeout(40)
    @DisplayName("被禁用的账号连重连都进不来，其他账号不受影响")
    void disabledAccountCannotReconnect() throws Exception {
        jdbc.update("UPDATE im_user SET status = 0 WHERE id = ?", USER_D);

        assertThatThrownBy(() -> connect(tokenD))
                .as("被禁用的 token 应在 CONNECT 阶段被拒")
                .isInstanceOf(ExecutionException.class);

        // 反证：同一个应用、同一时刻，正常账号照样连得上
        sessionA = connect(tokenA);
        assertThat(sessionA.isConnected()).isTrue();
    }

    // ---------------------------------------------------------------- 辅助

    private StompSession connect(String token) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        return client.connectAsync("ws://localhost:" + port + "/ws?token=" + token,
                        new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {
                        })
                .get(10, TimeUnit.SECONDS);
    }

    private LinkedBlockingQueue<JsonNode> subscribe(StompSession session, String destination) {
        LinkedBlockingQueue<JsonNode> queue = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.offer((JsonNode) payload);
            }
        });
        return queue;
    }

    private void disconnect(StompSession session) {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    private void insertUser(long id, String username, String nickname) {
        jdbc.update("""
                INSERT INTO im_user (id, username, password_hash, nickname, status)
                VALUES (?, ?, ?, ?, 1)
                """, id, username, "$2a$10$placeholderhashnotusedinthesetests0000000000000000000000", nickname);
    }
}
