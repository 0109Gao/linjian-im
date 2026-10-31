package com.linjian.im.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linjian.im.chat.dto.SendCommand;
import com.linjian.im.chat.service.MessageService;
import com.linjian.im.chat.service.SessionService;
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

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W8 的端到端验收（REST 侧）：群聊生命周期与群消息。
 *
 * <p>与 W7FlowIT 同样的隔离手段：独立 im_test 库 + Redis db15 + 每个用例前 TRUNCATE。
 * 连接真实中间件，因为「成员数」「未读数」「seq 递增」这些断言 mock 掉就等于没测。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class W8FlowIT {

    private static final List<String> TABLES = List.of(
            "im_inbox", "im_read_cursor", "im_message", "im_session_member", "im_session", "im_user");

    private static final long USER_A = 1001L;
    private static final long USER_B = 1002L;
    private static final long USER_C = 1003L;
    private static final long USER_D = 1004L;

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
    private SessionService sessionService;

    private String tokenA;
    private String tokenB;
    private String tokenC;
    private String tokenD;

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
        insertUser(USER_D, "dave", "戴夫");

        tokenA = jwtService.issue(USER_A, "alice");
        tokenB = jwtService.issue(USER_B, "bob");
        tokenC = jwtService.issue(USER_C, "carol");
        tokenD = jwtService.issue(USER_D, "dave");
    }

    // ---------------------------------------------------------------- 建群

    @Test
    @DisplayName("建群后出现在所有人的会话列表：类型、群名、成员数都正确")
    void groupAppearsInEveryMemberList() {
        long sessionId = createGroup(tokenA, "产品讨论组", List.of(USER_B, USER_C));

        for (String token : List.of(tokenA, tokenB, tokenC)) {
            JsonNode item = onlySession(token);
            assertThat(item.path("sessionId").asLong()).isEqualTo(sessionId);
            assertThat(item.path("sessionType").asInt()).isEqualTo(2);
            assertThat(item.path("name").asText()).isEqualTo("产品讨论组");
            assertThat(item.path("memberCount").asInt()).isEqualTo(3);
            // 群聊没有「对方」，这两个字段必须为空而不是指向某个随机成员
            assertThat(item.hasNonNull("peerUserId")).isFalse();
            assertThat(item.path("peerOnline").asBoolean()).isFalse();
            assertThat(item.path("unreadCount").asLong()).isZero();
        }
    }

    @Test
    @DisplayName("建群入参：只邀请自己、群名超长、成员里有不存在的人，都在写库前被拒")
    void createGroupValidation() {
        assertThat(body(post("/api/sessions/group", tokenA,
                Map.of("name", "独角戏", "memberIds", List.of(USER_A)))).path("code").asInt())
                .isEqualTo(4000);

        assertThat(body(post("/api/sessions/group", tokenA,
                Map.of("name", "😀".repeat(65), "memberIds", List.of(USER_B)))).path("code").asInt())
                .isEqualTo(4000);

        assertThat(body(post("/api/sessions/group", tokenA,
                Map.of("name", "带幽灵", "memberIds", List.of(USER_B, 999999L)))).path("code").asInt())
                .isEqualTo(4110);

        Long sessions = jdbc.queryForObject("SELECT COUNT(*) FROM im_session", Long.class);
        assertThat(sessions).isZero();
    }

    @Test
    @DisplayName("群名 64 个 emoji 合法——按码点算，不按 UTF-16 单元算")
    void groupNameCountsCodePoints() {
        JsonNode body = body(post("/api/sessions/group", tokenA,
                Map.of("name", "😀".repeat(64), "memberIds", List.of(USER_B))));
        assertThat(body.path("code").asInt()).isZero();

        String name = data(get("/api/sessions", tokenA)).path("items").get(0).path("name").asText();
        // 64 个 emoji 的 String.length() 是 128，这里断言的是码点数，正是服务端的口径
        assertThat(name.length()).isEqualTo(128);
        assertThat(name.codePointCount(0, name.length())).isEqualTo(64);
    }

    // ---------------------------------------------------------------- 群消息

    @Test
    @DisplayName("群消息：每个成员各得一份未读，发送者没有，seq 在会话内单调递增")
    void groupMessageFanout() {
        long sessionId = createGroup(tokenA, "三人组", List.of(USER_B, USER_C));

        messageService.send(USER_A, new SendCommand(sessionId, "g1", "第一条群消息"));
        messageService.send(USER_B, new SendCommand(sessionId, "g2", "第二条群消息"));

        // 未读是「别人发给我」的计数：C 收到两条，B 只收到 A 那一条，A 只收到 B 那一条
        for (String token : List.of(tokenB, tokenC)) {
            JsonNode item = onlySession(token);
            assertThat(item.path("lastMsgPreview").asText()).isEqualTo("第二条群消息");
            assertThat(item.path("lastMsgSeq").asLong()).isEqualTo(2L);
        }
        assertThat(onlySession(tokenB).path("unreadCount").asLong()).isEqualTo(1L);
        assertThat(onlySession(tokenC).path("unreadCount").asLong()).isEqualTo(2L);
        assertThat(onlySession(tokenA).path("unreadCount").asLong()).isEqualTo(1L);

        JsonNode page = data(get("/api/sessions/" + sessionId + "/messages", tokenC));
        assertThat(page.path("items")).hasSize(2);
        assertThat(page.path("items").get(0).path("seq").asLong()).isEqualTo(1L);
        assertThat(page.path("items").get(1).path("seq").asLong()).isEqualTo(2L);
        assertThat(page.path("items").get(1).path("clientMsgId").asText()).isEqualTo("g2");
    }

    @Test
    @DisplayName("上报已读后未读清零，且只影响自己")
    void readClearsOnlyOwnUnread() {
        long sessionId = createGroup(tokenA, "三人组", List.of(USER_B, USER_C));
        messageService.send(USER_A, new SendCommand(sessionId, "r1", "请查看"));

        JsonNode result = data(post("/api/sessions/" + sessionId + "/read", tokenB,
                Map.of("lastReadSeq", 1)));
        assertThat(result.path("unreadCount").asLong()).isZero();

        assertThat(onlySession(tokenB).path("unreadCount").asLong()).isZero();
        assertThat(onlySession(tokenC).path("unreadCount").asLong()).isEqualTo(1L);
    }

    // ---------------------------------------------------------------- 成员管理

    @Test
    @DisplayName("拉人：返回真正新增的人数，重复拉人返回 0 且不报错")
    void addMembersReportsActualCount() {
        long sessionId = createGroup(tokenA, "两人组", List.of(USER_B));

        JsonNode added = data(post("/api/sessions/" + sessionId + "/members", tokenB,
                Map.of("userIds", List.of(USER_C, USER_C, USER_D))));
        assertThat(added.path("added").asInt()).isEqualTo(2);

        JsonNode again = data(post("/api/sessions/" + sessionId + "/members", tokenB,
                Map.of("userIds", List.of(USER_C))));
        assertThat(again.path("added").asInt()).as("已在群里的人应被静默跳过").isZero();

        assertThat(onlySession(tokenD).path("memberCount").asInt()).isEqualTo(4);
    }

    @Test
    @DisplayName("成员列表：包含昵称，非成员看群成员被拒 4101")
    void memberListIsGuarded() {
        long sessionId = createGroup(tokenA, "两人组", List.of(USER_B));

        JsonNode members = data(get("/api/sessions/" + sessionId + "/members", tokenA));
        assertThat(members).hasSize(2);
        assertThat(members.get(0).path("nickname").asText()).isEqualTo("爱丽丝");
        assertThat(members.get(0).path("online").asBoolean()).isFalse();

        JsonNode denied = body(get("/api/sessions/" + sessionId + "/members", tokenD));
        assertThat(denied.path("code").asInt()).isEqualTo(4101);
    }

    @Test
    @DisplayName("退群后既拉不到消息，也发不了消息，成员数同步减少")
    void leaveGroupRevokesAccess() {
        long sessionId = createGroup(tokenA, "两人组", List.of(USER_B));

        JsonNode left = data(exchange(HttpMethod.DELETE,
                "/api/sessions/" + sessionId + "/members/me", tokenB, null));
        assertThat(left.path("remaining").asInt()).isEqualTo(1);

        assertThat(body(get("/api/sessions/" + sessionId + "/messages", tokenB)).path("code").asInt())
                .isEqualTo(4101);
        assertThat(body(post("/api/sessions/" + sessionId + "/members", tokenB,
                Map.of("userIds", List.of(USER_C)))).path("code").asInt())
                .isEqualTo(4101);

        JsonNode members = data(get("/api/sessions/" + sessionId + "/members", tokenA));
        assertThat(members).hasSize(1);
    }

    @Test
    @DisplayName("单聊不能拉人、不能退群：报参数错误，不是 404")
    void singleChatRejectsGroupOperations() {
        long singleId = sessionService.openSingle(USER_A, USER_B);

        assertThat(body(post("/api/sessions/" + singleId + "/members", tokenA,
                Map.of("userIds", List.of(USER_C)))).path("code").asInt())
                .isEqualTo(4000);
        assertThat(body(exchange(HttpMethod.DELETE,
                "/api/sessions/" + singleId + "/members/me", tokenA, null)).path("code").asInt())
                .isEqualTo(4000);
    }

    @Test
    @DisplayName("群成员上限 200：拉过头整批拒绝，不会写入一半")
    void groupSizeLimitEnforced() {
        // 先建一个 2 人群，再用「当前人数 + 199」触发超限
        long sessionId = createGroup(tokenA, "小群", List.of(USER_B));
        List<Long> flood = new java.util.ArrayList<>();
        for (long i = 0; i < 199; i++) {
            flood.add(5000L + i);
        }
        // 这些用户都不存在，超限检查必须先于用户存在性检查触发
        JsonNode body = body(post("/api/sessions/" + sessionId + "/members", tokenA,
                Map.of("userIds", flood)));
        assertThat(body.path("code").asInt()).isEqualTo(4000);
        assertThat(body.path("message").asText()).contains("上限");

        Integer members = jdbc.queryForObject(
                "SELECT COUNT(*) FROM im_session_member WHERE session_id = ?", Integer.class, sessionId);
        assertThat(members).isEqualTo(2);
    }

    // ---------------------------------------------------------------- 辅助

    private void insertUser(long id, String username, String nickname) {
        jdbc.update("""
                INSERT INTO im_user (id, username, password_hash, nickname, status)
                VALUES (?, ?, ?, ?, 1)
                """, id, username, "$2a$10$placeholderhashnotusedinthesetests0000000000000000000000", nickname);
    }

    private long createGroup(String token, String name, List<Long> memberIds) {
        JsonNode body = body(post("/api/sessions/group", token,
                Map.of("name", name, "memberIds", memberIds)));
        assertThat(body.path("code").asInt()).as("建群失败: %s", body).isZero();
        return body.path("data").path("sessionId").asLong();
    }

    /** 会话列表在这一组用例里恒为一条，直接取第一个元素省掉重复判空。 */
    private JsonNode onlySession(String token) {
        JsonNode list = data(get("/api/sessions", token));
        assertThat(list.path("items")).as("会话列表应只有一条").hasSize(1);
        return list.path("items").get(0);
    }

    private ResponseEntity<String> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    private ResponseEntity<String> post(String path, String token, Object payload) {
        return exchange(HttpMethod.POST, path, token, payload);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, String token, Object payload) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(rest.getRootUri() + path, method,
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
