package com.linjian.im.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linjian.im.chat.dto.SendCommand;
import com.linjian.im.chat.dto.SendResult;
import com.linjian.im.chat.service.MessageService;
import com.linjian.im.chat.service.SeqService;
import com.linjian.im.common.exception.BusinessException;
import com.linjian.im.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W7 的端到端验收（REST 侧）。
 *
 * <p>与 W6 的集成测试不同，这里<b>连真实的 MySQL 与 Redis</b>：落库、seq 分配、未读计数
 * 这三件事没有任何可以 mock 的余地，mock 掉它们就等于没测。</p>
 *
 * <p>隔离手段：独立的 im_test 库 + Redis db15 + 每个用例前 TRUNCATE。三项缺一项，
 * 「未读数等于 2」这种绝对断言就会被上一次运行的残留数据污染。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class W7FlowIT {

    private static final List<String> TABLES = List.of(
            "im_inbox", "im_read_cursor", "im_message", "im_session_member", "im_session",
            "im_admin_log", "im_user");

    private static final long USER_A = 1001L;
    private static final long USER_B = 1002L;
    private static final long USER_C = 1003L;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private MessageService messageService;
    @Autowired
    private SeqService seqService;

    private String tokenA;
    private String tokenB;
    private String tokenC;

    @BeforeEach
    void reset() {
        TABLES.forEach(t -> jdbc.execute("TRUNCATE TABLE " + t));
        Set<String> keys = redis.keys("im:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }

        insertUser(USER_A, "alice", "爱丽丝");
        insertUser(USER_B, "bob", "小鲍");
        insertUser(USER_C, "carol", "卡罗");

        tokenA = jwtService.issue(USER_A, "alice");
        tokenB = jwtService.issue(USER_B, "bob");
        tokenC = jwtService.issue(USER_C, "carol");
    }

    // ---------------------------------------------------------------- 判据 3：离线消息与未读数

    @Test
    @DisplayName("对方离线时消息落入收件箱，上线后能拉到且未读数正确")
    void offlineMessagesAreRecoverable() {
        long sessionId = openSession(tokenA, USER_B);

        messageService.send(USER_A, new SendCommand(sessionId, "c1", "第一条"));
        messageService.send(USER_A, new SendCommand(sessionId, "c2", "第二条"));

        JsonNode page = data(get("/api/sessions/" + sessionId + "/messages", tokenB));
        assertThat(page.path("items")).hasSize(2);
        assertThat(page.path("items").get(0).path("seq").asLong()).isEqualTo(1L);
        assertThat(page.path("items").get(1).path("content").asText()).isEqualTo("第二条");

        JsonNode list = data(get("/api/sessions", tokenB));
        assertThat(list.path("items")).hasSize(1);
        assertThat(list.path("items").get(0).path("unreadCount").asLong()).isEqualTo(2L);
        assertThat(list.path("items").get(0).path("peerNickname").asText()).isEqualTo("爱丽丝");
        assertThat(list.path("items").get(0).path("lastMsgPreview").asText()).isEqualTo("第二条");

        // 发送者自己没有未读
        JsonNode mine = data(get("/api/sessions", tokenA));
        assertThat(mine.path("items").get(0).path("unreadCount").asLong()).isZero();
    }

    // ---------------------------------------------------------------- 判据 4：重发幂等

    @Test
    @DisplayName("同一 clientMsgId 重发：返回首次 seq，库里只有一条")
    void resendIsIdempotent() {
        long sessionId = openSession(tokenA, USER_B);

        SendResult first = messageService.send(USER_A, new SendCommand(sessionId, "dup-1", "只应该存一条"));
        SendResult second = messageService.send(USER_A, new SendCommand(sessionId, "dup-1", "只应该存一条"));

        assertThat(first.duplicated()).isFalse();
        assertThat(second.duplicated()).isTrue();
        assertThat(second.seq()).isEqualTo(first.seq());
        assertThat(second.messageId()).isEqualTo(first.messageId());

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM im_message WHERE session_id = ?", Long.class, sessionId);
        assertThat(count).isEqualTo(1L);

        // 对方不应因为重发而多出一条未读
        JsonNode list = data(get("/api/sessions", tokenB));
        assertThat(list.path("items").get(0).path("unreadCount").asLong()).isEqualTo(1L);
    }

    @Test
    @DisplayName("断线重连补齐：按 afterSeq 只拿漏掉的那几条，不重复")
    void reconnectPullsOnlyMissingMessages() {
        long sessionId = openSession(tokenA, USER_B);
        for (int i = 1; i <= 5; i++) {
            messageService.send(USER_A, new SendCommand(sessionId, "m" + i, "消息" + i));
        }

        JsonNode page = data(get("/api/sessions/" + sessionId + "/messages?afterSeq=2", tokenB));
        List<Long> seqs = new ArrayList<>();
        page.path("items").forEach(n -> seqs.add(n.path("seq").asLong()));
        assertThat(seqs).containsExactly(3L, 4L, 5L);
    }

    @Test
    @DisplayName("向上翻历史：beforeSeq 返回更早的一页，且仍是升序")
    void historyPaging() {
        long sessionId = openSession(tokenA, USER_B);
        for (int i = 1; i <= 10; i++) {
            messageService.send(USER_A, new SendCommand(sessionId, "h" + i, "历史" + i));
        }

        JsonNode page = data(get("/api/sessions/" + sessionId + "/messages?beforeSeq=8&limit=3", tokenB));
        List<Long> seqs = new ArrayList<>();
        page.path("items").forEach(n -> seqs.add(n.path("seq").asLong()));
        assertThat(seqs).containsExactly(5L, 6L, 7L);
        assertThat(page.path("hasMore").asBoolean()).isTrue();
    }

    // ---------------------------------------------------------------- 判据 5：已读清零

    @Test
    @DisplayName("上报已读后收件箱清空、未读数归零")
    void readClearsInbox() {
        long sessionId = openSession(tokenA, USER_B);
        messageService.send(USER_A, new SendCommand(sessionId, "r1", "一"));
        messageService.send(USER_A, new SendCommand(sessionId, "r2", "二"));
        messageService.send(USER_A, new SendCommand(sessionId, "r3", "三"));

        // 只读到第 2 条：还剩 1 条未读，服务端不能替用户把没看的也标成已读
        JsonNode partial = data(post("/api/sessions/" + sessionId + "/read",
                tokenB, Map.of("lastReadSeq", 2)));
        assertThat(partial.path("unreadCount").asLong()).isEqualTo(1L);

        Long remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM im_inbox WHERE user_id = ? AND session_id = ?",
                Long.class, USER_B, sessionId);
        assertThat(remaining).isEqualTo(1L);

        JsonNode all = data(post("/api/sessions/" + sessionId + "/read",
                tokenB, Map.of("lastReadSeq", 3)));
        assertThat(all.path("unreadCount").asLong()).isZero();

        Long after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM im_inbox WHERE user_id = ? AND session_id = ?",
                Long.class, USER_B, sessionId);
        assertThat(after).isZero();

        // 游标不能因为乱序上报而倒退
        post("/api/sessions/" + sessionId + "/read", tokenB, Map.of("lastReadSeq", 1));
        Long cursor = jdbc.queryForObject(
                "SELECT last_read_seq FROM im_read_cursor WHERE user_id = ? AND session_id = ?",
                Long.class, USER_B, sessionId);
        assertThat(cursor).isEqualTo(3L);
    }

    // ---------------------------------------------------------------- 判据 6：越权

    @Test
    @DisplayName("第三方拿到 sessionId 也拉不到消息，返回 4101 而不是空数组")
    void thirdPartyCannotReadSession() {
        long sessionId = openSession(tokenA, USER_B);
        messageService.send(USER_A, new SendCommand(sessionId, "x1", "私密内容"));

        JsonNode body = body(get("/api/sessions/" + sessionId + "/messages", tokenC));
        assertThat(body.path("code").asInt()).isEqualTo(4101);
        assertThat(body.path("data").isMissingNode() || body.path("data").isNull()).isTrue();

        // 已读上报同样被拦住
        JsonNode read = body(post("/api/sessions/" + sessionId + "/read", tokenC, Map.of("lastReadSeq", 1)));
        assertThat(read.path("code").asInt()).isEqualTo(4101);
    }

    @Test
    @DisplayName("不存在的 sessionId 返回 4103，与会话存在但无权访问区分开")
    void missingSessionReturns4103() {
        JsonNode body = body(get("/api/sessions/123456789/messages", tokenA));
        assertThat(body.path("code").asInt()).isEqualTo(4103);
    }

    // ---------------------------------------------------------------- 判据 7、8：内容边界

    @Test
    @DisplayName("2000 个 emoji 能存取往返，不发生截断或编码损坏")
    void maxEmojiRoundTrip() {
        long sessionId = openSession(tokenA, USER_B);
        String emoji = "😀";
        String content = emoji.repeat(2000);

        SendResult result = messageService.send(USER_A, new SendCommand(sessionId, "emoji-1", content));
        assertThat(result.success()).isTrue();

        JsonNode page = data(get("/api/sessions/" + sessionId + "/messages", tokenB));
        String stored = page.path("items").get(0).path("content").asText();
        assertThat(stored).isEqualTo(content);
        assertThat(stored.codePointCount(0, stored.length())).isEqualTo(2000);
    }

    @Test
    @DisplayName("2001 个字符被拒（4102），且不产生消息")
    void overLimitRejected() {
        long sessionId = openSession(tokenA, USER_B);
        String content = "a".repeat(2001);

        assertThatThrownBy(() -> messageService.send(USER_A, new SendCommand(sessionId, "long-1", content)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2001");

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM im_message WHERE session_id = ?", Long.class, sessionId);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("纯空白被拒（4102）")
    void blankRejected() {
        long sessionId = openSession(tokenA, USER_B);
        assertThatThrownBy(() -> messageService.send(USER_A, new SendCommand(sessionId, "blank-1", "   ")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为空");
    }

    // ---------------------------------------------------------------- 判据 2 的持久化侧：seq 空洞

    @Test
    @DisplayName("seq 出现空洞时，拉取与未读数都不受影响")
    void seqGapDoesNotBreakReads() {
        long sessionId = openSession(tokenA, USER_B);
        messageService.send(USER_A, new SendCommand(sessionId, "g1", "第一条"));   // seq = 1
        long wasted = seqService.next(sessionId);                                  // 消耗 seq = 2，不插入
        messageService.send(USER_A, new SendCommand(sessionId, "g2", "第二条"));   // seq = 3

        assertThat(wasted).isEqualTo(2L);

        JsonNode page = data(get("/api/sessions/" + sessionId + "/messages", tokenB));
        List<Long> seqs = new ArrayList<>();
        page.path("items").forEach(n -> seqs.add(n.path("seq").asLong()));
        assertThat(seqs).containsExactly(1L, 3L);

        // 未读数用 COUNT(im_inbox)，不是 maxSeq - lastReadSeq，所以空洞不会多算
        JsonNode list = data(get("/api/sessions", tokenB));
        assertThat(list.path("items").get(0).path("unreadCount").asLong()).isEqualTo(2L);
    }

    // ---------------------------------------------------------------- 鉴权

    @Test
    @DisplayName("未登录访问 REST 返回 401 + 统一结构")
    void unauthenticatedReturns401() {
        ResponseEntity<String> response = get("/api/sessions", null);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(body(response).path("code").asInt()).isEqualTo(4010);
    }

    @Test
    @DisplayName("伪造 token 与无 token 等价")
    void forgedTokenRejected() {
        ResponseEntity<String> response = get("/api/sessions", "forged.token.value");
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- 会话懒创建

    @Test
    @DisplayName("双向发起落到同一个会话")
    void openIsBidirectional() {
        long fromA = openSession(tokenA, USER_B);
        long fromB = openSession(tokenB, USER_A);
        assertThat(fromA).isEqualTo(fromB);

        Long sessions = jdbc.queryForObject("SELECT COUNT(*) FROM im_session", Long.class);
        assertThat(sessions).isEqualTo(1L);
    }

    @Test
    @DisplayName("不能和自己开会话")
    void cannotOpenWithSelf() {
        JsonNode body = body(post("/api/sessions/open", tokenA, Map.of("peerUserId", USER_A)));
        assertThat(body.path("code").asInt()).isEqualTo(4000);
    }

    // ---------------------------------------------------------------- 辅助

    private void insertUser(long id, String username, String nickname) {
        jdbc.update("""
                INSERT INTO im_user (id, username, password_hash, nickname, status)
                VALUES (?, ?, ?, ?, 1)
                """, id, username, "$2a$10$placeholderhashnotusedinthesetests0000000000000000000000", nickname);
    }

    private long openSession(String token, long peerId) {
        JsonNode body = body(post("/api/sessions/open", token, Map.of("peerUserId", peerId)));
        assertThat(body.path("code").asInt()).as("打开会话失败: %s", body).isZero();
        return body.path("data").path("sessionId").asLong();
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(rest.getRootUri() + path, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> post(String path, String token, Object payload) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(rest.getRootUri() + path, HttpMethod.POST,
                new HttpEntity<>(payload, headers), String.class);
    }

    private JsonNode body(ResponseEntity<String> response) {
        try {
            return json.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON: " + response.getBody(), e);
        }
    }

    private JsonNode data(ResponseEntity<String> response) {
        JsonNode body = body(response);
        assertThat(body.path("code").asInt()).as("接口返回失败: %s", body).isZero();
        return body.path("data");
    }
}
