package com.linjian.im.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linjian.im.chat.dto.SendCommand;
import com.linjian.im.chat.service.MessageService;
import com.linjian.im.chat.service.SessionService;
import com.linjian.im.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W8 的实时链路验收：群广播、订阅鉴权、多端回推、已读同步。
 *
 * <p>四件事都只能在真实 STOMP 连接上验证：目的地是否正确、订阅是否被拦下、
 * 自己发的消息会不会回到自己手里、已读事件会不会到达另一台设备。
 * 直接调 Service 查库可以证明数据对了，但证明不了「谁收到了」。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class W8GroupIT {

    private static final List<String> TABLES = List.of(
            "im_inbox", "im_read_cursor", "im_message", "im_session_member", "im_session",
            "im_admin_log", "im_user");

    private static final long USER_A = 3001L;
    private static final long USER_B = 3002L;
    private static final long USER_C = 3003L;
    private static final long USER_D = 3004L;

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
    @Autowired
    private MessageService messageService;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private TestRestTemplate rest;

    private WebSocketStompClient client;
    private StompSession sessionA;
    private StompSession sessionB;
    private StompSession sessionC1;
    private StompSession sessionC2;
    private StompSession sessionD;

    private String tokenA;
    private String tokenB;
    private String tokenC;
    private String tokenD;

    @BeforeEach
    void setUp() {
        TABLES.forEach(t -> jdbc.execute("TRUNCATE TABLE " + t));
        Set<String> keys = redis.keys("im:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }

        insertUser(USER_A, "alice", "爱丽丝");
        insertUser(USER_B, "bob", "小鲍");
        insertUser(USER_C, "carol", "卡罗");
        insertUser(USER_D, "dave", "戴夫");

        tokenA = jwtService.issue(USER_A, "alice");
        tokenB = jwtService.issue(USER_B, "bob");
        tokenC = jwtService.issue(USER_C, "carol");
        tokenD = jwtService.issue(USER_D, "dave");

        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @AfterEach
    void tearDown() {
        for (StompSession s : new StompSession[]{sessionA, sessionB, sessionC1, sessionC2, sessionD}) {
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
    @DisplayName("群消息：一次广播，两个成员都收到，未读各记一份")
    void groupMessageReachesEverySubscriber() throws Exception {
        long sessionId = sessionService.createGroup(USER_A, "三人组", List.of(USER_B, USER_C));

        sessionA = connect(tokenA);
        sessionB = connect(tokenB);
        StompSession sessionC = connect(tokenC);
        sessionC1 = sessionC;

        LinkedBlockingQueue<JsonNode> bTopic = subscribe(sessionB, "/topic/session." + sessionId);
        LinkedBlockingQueue<JsonNode> cTopic = subscribe(sessionC, "/topic/session." + sessionId);
        LinkedBlockingQueue<JsonNode> aAcks = subscribe(sessionA, "/user/queue/ack");
        Thread.sleep(400);

        sessionA.send("/app/chat.send", new SendCommand(sessionId, "grp-1", "大家好"));

        JsonNode ack = aAcks.poll(10, TimeUnit.SECONDS);
        assertThat(ack).as("发送方应收到 ack").isNotNull();
        assertThat(ack.path("success").asBoolean()).isTrue();
        long seq = ack.path("seq").asLong();

        JsonNode fromB = bTopic.poll(10, TimeUnit.SECONDS);
        JsonNode fromC = cTopic.poll(10, TimeUnit.SECONDS);
        assertThat(fromB).as("B 应通过群主题收到").isNotNull();
        assertThat(fromC).as("C 应通过群主题收到").isNotNull();
        assertThat(fromB.path("content").asText()).isEqualTo("大家好");
        assertThat(fromB.path("seq").asLong()).isEqualTo(seq);
        assertThat(fromC.path("seq").asLong()).isEqualTo(seq);
        assertThat(fromB.path("senderId").asLong()).isEqualTo(USER_A);

        // 未读行：两个成员各一条，发送者没有
        assertThat(unread(USER_B, sessionId)).isEqualTo(1L);
        assertThat(unread(USER_C, sessionId)).isEqualTo(1L);
        assertThat(unread(USER_A, sessionId)).isZero();
    }

    @Test
    @Timeout(40)
    @DisplayName("非成员订阅群主题被静默拦下：收不到消息，但连接不被断开")
    void nonMemberCannotSubscribeGroupTopic() throws Exception {
        long sessionId = sessionService.createGroup(USER_A, "三人组", List.of(USER_B, USER_C));

        sessionA = connect(tokenA);
        sessionB = connect(tokenB);
        sessionD = connect(tokenD);

        LinkedBlockingQueue<JsonNode> bTopic = subscribe(sessionB, "/topic/session." + sessionId);
        // D 不在群里，这条 SUBSCRIBE 应当被 GroupTopicAuthInterceptor 拦下
        LinkedBlockingQueue<JsonNode> dTopic = subscribe(sessionD, "/topic/session." + sessionId);
        LinkedBlockingQueue<JsonNode> aAcks = subscribe(sessionA, "/user/queue/ack");
        Thread.sleep(400);

        sessionA.send("/app/chat.send", new SendCommand(sessionId, "grp-2", "这条不该给 D"));

        assertThat(aAcks.poll(10, TimeUnit.SECONDS)).isNotNull();
        // 用 B 的收货作为「广播确实发生了」的屏障，否则 D 收不到可能只是消息还没发
        assertThat(bTopic.poll(10, TimeUnit.SECONDS)).as("B 应收到").isNotNull();
        assertThat(dTopic.poll(800, TimeUnit.MILLISECONDS)).as("非成员不该收到群消息").isNull();

        // 关键取舍：拦订阅用的是「丢弃这一帧」而不是抛异常，所以连接必须还活着
        assertThat(sessionD.isConnected()).as("一次越权订阅不该掐断连接").isTrue();
    }

    @Test
    @Timeout(40)
    @DisplayName("单聊：发送者自己也会收到推送，用于他的其他设备同步")
    void senderReceivesOwnMessageInSingleChat() throws Exception {
        long sessionId = sessionService.openSingle(USER_A, USER_B);

        sessionA = connect(tokenA);
        LinkedBlockingQueue<JsonNode> echo = subscribe(sessionA, "/user/queue/message");
        LinkedBlockingQueue<JsonNode> aAcks = subscribe(sessionA, "/user/queue/ack");
        Thread.sleep(400);

        sessionA.send("/app/chat.send", new SendCommand(sessionId, "self-1", "自己也能收到"));

        JsonNode ack = aAcks.poll(10, TimeUnit.SECONDS);
        assertThat(ack).isNotNull();
        long seq = ack.path("seq").asLong();

        JsonNode received = echo.poll(10, TimeUnit.SECONDS);
        assertThat(received).as("W8 起发送者自己也收推送").isNotNull();
        assertThat(received.path("seq").asLong()).isEqualTo(seq);
        assertThat(received.path("senderId").asLong()).isEqualTo(USER_A);
        // clientMsgId 是客户端把「本地乐观插入」和「服务端回推」认成同一条的唯一依据
        assertThat(received.path("clientMsgId").asText()).isEqualTo("self-1");
    }

    @Test
    @Timeout(40)
    @DisplayName("已读同步：一端上报，本人另一端从 /user/queue/read 收到清零事件")
    void readEventReachesOtherDevice() throws Exception {
        long sessionId = sessionService.createGroup(USER_A, "两人组", List.of(USER_C));
        messageService.send(USER_A, new SendCommand(sessionId, "read-1", "看一下"));

        sessionC1 = connect(tokenC);
        sessionC2 = connect(tokenC);
        LinkedBlockingQueue<JsonNode> readEvents = subscribe(sessionC2, "/user/queue/read");
        Thread.sleep(400);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenC);
        rest.exchange(rest.getRootUri() + "/api/sessions/" + sessionId + "/read",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("lastReadSeq", 1), headers),
                String.class);

        JsonNode event = readEvents.poll(10, TimeUnit.SECONDS);
        assertThat(event).as("另一端应收到已读事件").isNotNull();
        assertThat(event.path("sessionId").asLong()).isEqualTo(sessionId);
        assertThat(event.path("lastReadSeq").asLong()).isEqualTo(1L);
        assertThat(event.path("unreadCount").asLong()).isZero();
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

    private long unread(long userId, long sessionId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM im_inbox WHERE user_id = ? AND session_id = ?",
                Long.class, userId, sessionId);
        return count == null ? 0 : count;
    }

    private void insertUser(long id, String username, String nickname) {
        jdbc.update("""
                INSERT INTO im_user (id, username, password_hash, nickname, status)
                VALUES (?, ?, ?, ?, 1)
                """, id, username, "$2a$10$placeholderhashnotusedinthesetests0000000000000000000000", nickname);
    }
}
