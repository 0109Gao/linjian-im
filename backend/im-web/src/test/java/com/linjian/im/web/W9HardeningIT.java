package com.linjian.im.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linjian.im.chat.dto.SendCommand;
import com.linjian.im.chat.dto.SendResult;
import com.linjian.im.chat.service.MessageService;
import com.linjian.im.chat.service.SeqService;
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
 * W9 收尾阶段补的三块「上线前才会痛」的能力：序号计数自愈、管理员操作审计、禁用即踢线。
 *
 * <p>这三块的共同点是<b>它们都不影响正常流程走通</b>：不重建计数，只要 Redis 不丢数据一切正常；
 * 不记审计，功能一点不缺；不踢线，用户下次操作时也会被拦。所以它们全都没法靠
 * 「点一遍功能」发现缺失，只能靠针对性断言——这也是这组用例存在的唯一理由。</p>
 *
 * <p>踢线不在这个类里，因为它需要真实的 WebSocket 连接，放在 {@code W9DisabledIT}。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class W9HardeningIT {

    private static final List<String> TABLES = List.of(
            "im_inbox", "im_read_cursor", "im_message", "im_session_member", "im_session",
            "im_admin_log", "im_user");

    private static final long USER_ADMIN = 9001L;
    private static final long USER_ADMIN_2 = 9002L;
    private static final long USER_A = 1001L;
    private static final long USER_B = 1002L;

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
    private SessionService sessionService;
    @Autowired
    private MessageService messageService;
    @Autowired
    private SeqService seqService;

    private String tokenAdmin;
    private String tokenAdmin2;
    private String tokenA;
    private String tokenB;

    @BeforeEach
    void reset() {
        TABLES.forEach(t -> jdbc.execute("TRUNCATE TABLE " + t));
        Set<String> keys = redis.keys("im:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }

        insertUser(USER_ADMIN, "root", "根管理员", 1);
        insertUser(USER_ADMIN_2, "root2", "第二管理员", 1);
        insertUser(USER_A, "alice", "爱丽丝", 0);
        insertUser(USER_B, "bob", "小鲍", 0);

        tokenAdmin = jwtService.issue(USER_ADMIN, "root", 1);
        tokenAdmin2 = jwtService.issue(USER_ADMIN_2, "root2", 1);
        tokenA = jwtService.issue(USER_A, "alice");
        tokenB = jwtService.issue(USER_B, "bob");
    }

    // ---------------------------------------------------------------- 序号计数自愈

    @Test
    @DisplayName("Redis 计数丢失后重建：计数回到库内最大值，之后发的消息不再撞唯一键")
    void rebuildRestoresLostCounter() {
        long sessionId = sessionService.openSingle(USER_A, USER_B);
        messageService.send(USER_A, new SendCommand(sessionId, "c1", "第一条"));
        messageService.send(USER_A, new SendCommand(sessionId, "c2", "第二条"));

        // 模拟 FLUSHALL / 换实例没接上 AOF
        redis.delete("im:seq:" + sessionId);
        assertThat(seqService.current(sessionId)).isZero();

        SeqService.RebuildResult result = seqService.rebuildAll();

        assertThat(result.raised()).as("应修正一个会话").isEqualTo(1);
        assertThat(seqService.current(sessionId))
                .as("计数必须被推到库内最大 seq，而不是停在被清空后的 0")
                .isEqualTo(2);

        // 真正的判据：重建之后发消息能成功，且序号接着往下走。
        // 不重建的话这里会拿到 seq=1，撞 uk_session_seq，整条消息插入失败。
        SendResult sent = messageService.send(USER_A, new SendCommand(sessionId, "c3", "第三条"));
        assertThat(sent.success()).isTrue();
        assertThat(sent.seq()).isEqualTo(3L);
    }

    @Test
    @DisplayName("重建是幂等的，且不会把已经更高的计数压回去")
    void rebuildNeverLowersCounter() {
        long sessionId = sessionService.openSingle(USER_A, USER_B);
        messageService.send(USER_A, new SendCommand(sessionId, "c1", "第一条"));
        seqService.rebuildAll();

        // 模拟「分配过但插入失败」留下的空洞：计数比库里的最大 seq 更大
        seqService.next(sessionId);
        seqService.next(sessionId);
        long beforeRebuild = seqService.current(sessionId);
        assertThat(beforeRebuild).isEqualTo(3L);   // 库里 1 条占掉 1 号，又空发了 2 个号

        SeqService.RebuildResult second = seqService.rebuildAll();

        assertThat(second.raised()).as("没有需要修正的会话").isZero();
        assertThat(seqService.current(sessionId))
                .as("空洞必须保留：把计数压回去会让下一次 INCR 重新发出已经用过的号")
                .isEqualTo(beforeRebuild);
    }

    @Test
    @DisplayName("管理端可以不停机重建序号计数，操作本身也会留痕")
    void adminCanTriggerRebuildAndItIsAudited() {
        long sessionId = sessionService.openSingle(USER_A, USER_B);
        messageService.send(USER_A, new SendCommand(sessionId, "c1", "第一条"));
        redis.delete("im:seq:" + sessionId);

        JsonNode data = data(post("/api/admin/maintenance/seq/rebuild", tokenAdmin));

        assertThat(data.path("sessions").asInt()).isEqualTo(1);
        assertThat(data.path("raised").asInt()).isEqualTo(1);
        assertThat(seqService.current(sessionId)).isEqualTo(1);

        JsonNode logs = data(get("/api/admin/logs?action=SEQ_REBUILD", tokenAdmin));
        assertThat(logs.path("total").asLong()).isEqualTo(1);
        assertThat(logs.path("items").get(0).path("targetType").asText()).isEqualTo("SYSTEM");
    }

    // ---------------------------------------------------------------- 管理员操作审计

    @Test
    @DisplayName("禁用账号留下一条可查的审计记录：谁、对谁、什么时候、从哪来")
    void disablingAccountIsAudited() {
        updateStatus(USER_B, 0, tokenAdmin);

        JsonNode logs = data(get("/api/admin/logs?action=USER_DISABLE", tokenAdmin));
        assertThat(logs.path("total").asLong()).isEqualTo(1);
        JsonNode row = logs.path("items").get(0);

        assertThat(row.path("adminId").asLong()).isEqualTo(USER_ADMIN);
        assertThat(row.path("adminName").asText()).isEqualTo("root");
        assertThat(row.path("actionText").asText()).isEqualTo("禁用账号");
        assertThat(row.path("targetType").asText()).isEqualTo("USER");
        assertThat(row.path("targetId").asLong()).isEqualTo(USER_B);
        assertThat(row.path("detail").asText()).contains("bob").contains("禁用");
        assertThat(row.path("ip").asText()).isNotBlank();
        assertThat(row.path("createdAt").asText()).isNotBlank();
    }

    @Test
    @DisplayName("恢复账号记成另一个动作，不会和禁用混在一起")
    void enablingAccountIsAuditedSeparately() {
        updateStatus(USER_B, 0, tokenAdmin);
        updateStatus(USER_B, 1, tokenAdmin);

        assertThat(data(get("/api/admin/logs?action=USER_DISABLE", tokenAdmin)).path("total").asLong())
                .isEqualTo(1);
        JsonNode enable = data(get("/api/admin/logs?action=USER_ENABLE", tokenAdmin));
        assertThat(enable.path("total").asLong()).isEqualTo(1);
        assertThat(enable.path("items").get(0).path("detail").asText()).contains("正常");
    }

    @Test
    @DisplayName("查看消息会留痕，但 60 秒内同一会话只记一条——否则翻页就能把日志刷满")
    void messageAuditIsLoggedOncePerMinute() {
        long sessionId = sessionService.openSingle(USER_A, USER_B);
        messageService.send(USER_A, new SendCommand(sessionId, "c1", "被审计的内容"));
        String path = "/api/admin/sessions/" + sessionId + "/messages";

        data(get(path, tokenAdmin));
        data(get(path + "?beforeSeq=5", tokenAdmin));
        data(get(path, tokenAdmin));

        Long mine = jdbc.queryForObject(
                "SELECT COUNT(*) FROM im_admin_log WHERE action = 'MSG_AUDIT_VIEW' AND admin_id = ?",
                Long.class, USER_ADMIN);
        assertThat(mine).as("同一管理员同一会话在窗口内只应留一行").isEqualTo(1L);

        // 去重维度是「人 + 会话」，不是全局：另一个管理员看同一个会话必须另记一条，
        // 否则审计会漏掉「第二个人也看过」这个事实。
        data(get(path, tokenAdmin2));
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM im_admin_log WHERE action = 'MSG_AUDIT_VIEW'", Long.class);
        assertThat(total).isEqualTo(2L);
    }

    @Test
    @DisplayName("普通用户查不到操作日志——审计记录本身就是敏感数据")
    void normalUserCannotReadLogs() {
        ResponseEntity<String> response = get("/api/admin/logs", tokenA);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(body(response).path("code").asInt()).isEqualTo(4030);
    }

    @Test
    @DisplayName("日志支持按动作与关键字筛选，且筛选后总数与列表一致")
    void logsAreFilterable() {
        updateStatus(USER_B, 0, tokenAdmin);
        updateStatus(USER_A, 0, tokenAdmin);

        JsonNode filtered = data(get("/api/admin/logs?keyword=bob", tokenAdmin));
        assertThat(filtered.path("total").asLong()).isEqualTo(1);
        assertThat(filtered.path("items")).hasSize(1);
        assertThat(filtered.path("items").get(0).path("targetId").asLong()).isEqualTo(USER_B);
    }

    // ---------------------------------------------------------------- 辅助

    private void updateStatus(long targetId, int status, String token) {
        ResponseEntity<String> response = exchange(HttpMethod.PUT,
                "/api/admin/users/" + targetId + "/status", token, Map.of("status", status));
        data(response);
    }

    private void insertUser(long id, String username, String nickname, int role) {
        jdbc.update("""
                INSERT INTO im_user (id, username, password_hash, nickname, status, role)
                VALUES (?, ?, ?, ?, 1, ?)
                """, id, username,
                "$2a$10$placeholderhashnotusedinthesetests0000000000000000000000", nickname, role);
    }

    private ResponseEntity<String> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    private ResponseEntity<String> post(String path, String token) {
        return exchange(HttpMethod.POST, path, token, null);
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
