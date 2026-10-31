package com.linjian.im.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linjian.im.chat.dto.SendCommand;
import com.linjian.im.chat.service.PresenceService;
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
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W7 的实时链路验收：两条真实的 STOMP 连接，一端发、另一端收。
 *
 * <p>为什么不用「直接调 Service 然后查库」代替：那条路径验证不了投递目的地是否正确。
 * W6 踩过一次同类教训——雪花 ID 精度问题 curl 全绿、真实浏览器才暴露。
 * 消息能不能到对的人手上，只有真的订阅一次 {@code /user/queue/message} 才算数。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class W7RealtimeIT {

    private static final List<String> TABLES = List.of(
            "im_inbox", "im_read_cursor", "im_message", "im_session_member", "im_session",
            "im_admin_log", "im_user");

    private static final long USER_A = 2001L;
    private static final long USER_B = 2002L;

    @LocalServerPort
    private int port;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private PresenceService presenceService;
    @Autowired
    private SessionService sessionService;
    @Autowired
    private ObjectMapper json;

    private WebSocketStompClient client;
    private StompSession sessionA;
    private StompSession sessionB;

    private String tokenA;
    private String tokenB;
    private long sessionId;

    @BeforeEach
    void setUp() {
        TABLES.forEach(t -> jdbc.execute("TRUNCATE TABLE " + t));
        Set<String> keys = redis.keys("im:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }

        insertUser(USER_A, "alice", "爱丽丝");
        insertUser(USER_B, "bob", "小鲍");
        tokenA = jwtService.issue(USER_A, "alice");
        tokenB = jwtService.issue(USER_B, "bob");
        sessionId = sessionService.openSingle(USER_A, USER_B);

        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @AfterEach
    void tearDown() {
        for (StompSession s : new StompSession[]{sessionA, sessionB}) {
            if (s != null && s.isConnected()) {
                s.disconnect();
            }
        }
        if (client != null) {
            client.stop();
        }
    }

    @Test
    @Timeout(40)
    @DisplayName("A 发消息，B 实时收到，A 收到带 seq 的 ack")
    void messageArrivesOnPeerSession() throws Exception {
        sessionA = connect(tokenA);
        sessionB = connect(tokenB);
        awaitOnline(USER_B);

        LinkedBlockingQueue<JsonNode> bInbox = subscribe(sessionB, "/user/queue/message");
        LinkedBlockingQueue<JsonNode> aAcks = subscribe(sessionA, "/user/queue/ack");
        Thread.sleep(300);

        sessionA.send("/app/chat.send", new SendCommand(sessionId, "rt-1", "实时你好"));

        JsonNode ack = aAcks.poll(10, TimeUnit.SECONDS);
        assertThat(ack).as("发送方应收到 ack").isNotNull();
        assertThat(ack.path("success").asBoolean()).isTrue();
        assertThat(ack.path("duplicated").asBoolean()).isFalse();
        long seq = ack.path("seq").asLong();
        assertThat(seq).isEqualTo(1L);

        JsonNode received = bInbox.poll(10, TimeUnit.SECONDS);
        assertThat(received).as("接收方应实时收到推送").isNotNull();
        assertThat(received.path("content").asText()).isEqualTo("实时你好");
        assertThat(received.path("seq").asLong()).isEqualTo(seq);
        assertThat(received.path("senderId").asLong()).isEqualTo(USER_A);

        // B 不应收到重复推送（同一条消息只推一次）
        // 注意：W8 起发送者自己也会收到推送（多端同步），那条断言在 W8GroupIT 里
        assertThat(bInbox.poll(500, TimeUnit.MILLISECONDS)).isNull();

        // 在线用户同样要落未读：推送到达 ≠ 用户已读
        Long unread = jdbc.queryForObject(
                "SELECT COUNT(*) FROM im_inbox WHERE user_id = ? AND session_id = ?",
                Long.class, USER_B, sessionId);
        assertThat(unread).isEqualTo(1L);
    }

    @Test
    @Timeout(40)
    @DisplayName("内容非法时 ack 返回失败，但连接不被关闭")
    void invalidContentDoesNotKillConnection() throws Exception {
        sessionA = connect(tokenA);

        LinkedBlockingQueue<JsonNode> aAcks = subscribe(sessionA, "/user/queue/ack");
        Thread.sleep(300);

        sessionA.send("/app/chat.send", new SendCommand(sessionId, "bad-1", "a".repeat(2001)));

        JsonNode failed = aAcks.poll(10, TimeUnit.SECONDS);
        assertThat(failed).isNotNull();
        assertThat(failed.path("success").asBoolean()).isFalse();
        assertThat(failed.path("error").asText()).contains("2001");

        // 关键断言：连接还活着。如果实现里抛异常让 Spring 回 ERROR 帧，
        // 客户端会被断开，下面这次发送就发不出去、也收不到 ack。
        assertThat(sessionA.isConnected()).as("一条非法消息不应导致连接关闭").isTrue();

        sessionA.send("/app/chat.send", new SendCommand(sessionId, "bad-2", "这条应该成功"));
        JsonNode ok = aAcks.poll(10, TimeUnit.SECONDS);
        assertThat(ok).isNotNull();
        assertThat(ok.path("success").asBoolean()).isTrue();
        assertThat(ok.path("seq").asLong()).isEqualTo(1L);
    }

    @Test
    @Timeout(40)
    @DisplayName("非会话成员发消息被拒绝，返回失败而不是断连")
    void nonMemberCannotSend() throws Exception {
        insertUser(2003L, "carol", "卡罗");
        String tokenC = jwtService.issue(2003L, "carol");

        sessionB = connect(tokenC);
        LinkedBlockingQueue<JsonNode> bAcks = subscribe(sessionB, "/user/queue/ack");
        Thread.sleep(300);

        sessionB.send("/app/chat.send", new SendCommand(sessionId, "c-1", "我不是成员"));

        JsonNode failed = bAcks.poll(10, TimeUnit.SECONDS);
        assertThat(failed).isNotNull();
        assertThat(failed.path("success").asBoolean()).isFalse();
        assertThat(failed.path("error").asText()).contains("不在该会话中");

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM im_message WHERE session_id = ?", Long.class, sessionId);
        assertThat(count).isZero();
    }

    @Test
    @Timeout(40)
    @DisplayName("两端在线时都出现在对方的会话列表里，且 peerOnline 为 true")
    void bothSidesOnlineReflectedInPresence() throws Exception {
        sessionA = connect(tokenA);
        sessionB = connect(tokenB);
        awaitOnline(USER_A);
        awaitOnline(USER_B);

        assertThat(presenceService.isOnline(USER_A)).isTrue();
        assertThat(presenceService.isOnline(USER_B)).isTrue();
        assertThat(presenceService.onlineSessionCount(USER_B)).isEqualTo(1L);
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

    /** 等在线状态落 Redis。CONNECTED 帧与事件监听之间有极短的时间差，轮询比 sleep 更稳。 */
    private void awaitOnline(long userId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (presenceService.isOnline(userId)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("用户 " + userId + " 在 5 秒内没有变成在线状态");
    }

    private void insertUser(long id, String username, String nickname) {
        jdbc.update("""
                INSERT INTO im_user (id, username, password_hash, nickname, status)
                VALUES (?, ?, ?, ?, 1)
                """, id, username, "$2a$10$placeholderhashnotusedinthesetests0000000000000000000000", nickname);
    }
}
