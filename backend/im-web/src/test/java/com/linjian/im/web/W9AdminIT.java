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
 * W9 管理端的端到端验收。
 *
 * <p>这一组用例的核心不是「接口能返回数据」，而是三条边界：</p>
 * <ol>
 *   <li><b>普通用户进不来</b>——{@code /api/admin/**} 必须 403，且错误码是 4030 而不是 4010。
 *       401 会让前端去跳登录页，用户登录完再进来还是 403，形成一个死循环。</li>
 *   <li><b>禁用立即生效</b>——同一个 token 在禁用前后必须得到不同结果。
 *       这条是整个 W9 里最容易写成「看起来能跑、其实没生效」的地方：如果
 *       {@code AccountStatusFilter} 没有注册进过滤链，测试里的禁用操作照样会返回 200，
 *       只有断言「禁用后这个 token 用不了」才能发现。</li>
 *   <li><b>管理员能看别人的会话</b>——审计功能的意义就在于此，
 *       如果它还要求成员身份，那它和用户端接口没有区别。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class W9AdminIT {

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

    // ---------------------------------------------------------------- 权限边界

    @Test
    @DisplayName("普通用户访问管理端返回 403 + 4030，而不是 401——401 会让前端跳登录页后死循环")
    void normalUserGets403NotUnauthorized() {
        for (String path : List.of(
                "/api/admin/stats", "/api/admin/users", "/api/admin/sessions", "/api/admin/online")) {
            ResponseEntity<String> response = get(path, tokenA);
            assertThat(response.getStatusCode().value()).as(path).isEqualTo(403);
            assertThat(body(response).path("code").asInt()).as(path).isEqualTo(4030);
        }
    }

    @Test
    @DisplayName("不带 token 访问管理端返回 401 + 4010")
    void anonymousGets401() {
        ResponseEntity<String> response = get("/api/admin/stats", null);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(body(response).path("code").asInt()).isEqualTo(4010);
    }

    @Test
    @DisplayName("管理员可以访问概览，六个指标都是真实数字而不是 0")
    void adminSeesRealStats() {
        long sessionId = sessionService.openSingle(USER_A, USER_B);
        messageService.send(USER_A, new SendCommand(sessionId, "c1", "统计用消息"));

        JsonNode stats = data(get("/api/admin/stats", tokenAdmin));
        assertThat(stats.path("userCount").asLong()).isEqualTo(4L);
        assertThat(stats.path("sessionCount").asLong()).isEqualTo(1L);
        assertThat(stats.path("messageCount").asLong()).isEqualTo(1L);
        assertThat(stats.path("todayMessageCount").asLong()).isEqualTo(1L);
        assertThat(stats.path("groupCount").asLong()).isZero();
        assertThat(stats.path("onlineCount").asLong()).isZero();
    }

    // ---------------------------------------------------------------- 用户管理

    @Test
    @DisplayName("用户列表包含已禁用账号——否则禁用后那个账号从后台消失，再也启用不回来")
    void userListIncludesDisabledAccounts() {
        patch("/api/admin/users/" + USER_B + "/status", tokenAdmin, Map.of("status", 0));

        JsonNode all = data(get("/api/admin/users?size=50", tokenAdmin));
        assertThat(all.path("total").asLong()).isEqualTo(4L);
        assertThat(all.path("items")).hasSize(4);

        JsonNode disabled = null;
        for (JsonNode item : all.path("items")) {
            if (item.path("id").asLong() == USER_B) {
                disabled = item;
            }
        }
        assertThat(disabled).as("被禁用的账号仍应出现在列表里").isNotNull();
        assertThat(disabled.path("status").asInt()).isZero();

        JsonNode searched = data(get("/api/admin/users?keyword=bob", tokenAdmin));
        assertThat(searched.path("total").asLong()).isEqualTo(1L);
        assertThat(searched.path("items").get(0).path("username").asText()).isEqualTo("bob");
    }

    @Test
    @DisplayName("禁用后，同一个 token 的下一次请求立即被拒（403 + 4113）")
    void disablingTakesEffectOnExistingToken() {
        // 禁用前：能正常访问
        assertThat(get("/api/sessions", tokenB).getStatusCode().value()).isEqualTo(200);

        patch("/api/admin/users/" + USER_B + "/status", tokenAdmin, Map.of("status", 0));

        // 禁用后：token 没有过期、签名照样有效，但账号状态闸应该拦下它
        ResponseEntity<String> blocked = get("/api/sessions", tokenB);
        assertThat(blocked.getStatusCode().value()).isEqualTo(403);
        assertThat(body(blocked).path("code").asInt()).isEqualTo(4113);

        // 别的用户不受影响
        assertThat(get("/api/sessions", tokenA).getStatusCode().value()).isEqualTo(200);

        // 重新启用后恢复
        patch("/api/admin/users/" + USER_B + "/status", tokenAdmin, Map.of("status", 1));
        assertThat(get("/api/sessions", tokenB).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("不能禁用自己，也不能禁用其他管理员——两条规则都是为了不把后台锁死")
    void cannotDisableSelfOrOtherAdmin() {
        JsonNode self = body(patch("/api/admin/users/" + USER_ADMIN + "/status", tokenAdmin,
                Map.of("status", 0)));
        assertThat(self.path("code").asInt()).isEqualTo(4120);

        JsonNode other = body(patch("/api/admin/users/" + USER_ADMIN_2 + "/status", tokenAdmin,
                Map.of("status", 0)));
        assertThat(other.path("code").asInt()).isEqualTo(4121);

        // 两个管理员都还活着
        assertThat(get("/api/admin/stats", tokenAdmin).getStatusCode().value()).isEqualTo(200);
        assertThat(get("/api/admin/stats", tokenAdmin2).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("status 只能取 0/1，传别的值在写库前被拒")
    void statusMustBeZeroOrOne() {
        assertThat(body(patch("/api/admin/users/" + USER_B + "/status", tokenAdmin,
                Map.of("status", 7))).path("code").asInt()).isEqualTo(4000);
        assertThat(body(patch("/api/admin/users/" + USER_B + "/status", tokenAdmin,
                Map.of())).path("code").asInt()).isEqualTo(4000);
    }

    // ---------------------------------------------------------------- 消息审计

    @Test
    @DisplayName("管理员不是会话成员，仍能查看该会话的全部消息——审计功能的意义所在")
    void adminCanAuditSessionHeIsNotIn() {
        long sessionId = sessionService.openSingle(USER_A, USER_B);
        messageService.send(USER_A, new SendCommand(sessionId, "a1", "第一条"));
        messageService.send(USER_B, new SendCommand(sessionId, "b1", "第二条"));

        JsonNode page = data(get("/api/admin/sessions/" + sessionId + "/messages", tokenAdmin));
        assertThat(page.path("items")).hasSize(2);
        assertThat(page.path("items").get(0).path("content").asText()).isEqualTo("第一条");
        assertThat(page.path("items").get(1).path("content").asText()).isEqualTo("第二条");

        // 会话列表里也能找到它，且成员数正确
        JsonNode sessions = data(get("/api/admin/sessions?keyword=" + sessionId, tokenAdmin));
        assertThat(sessions.path("total").asLong()).isEqualTo(1L);
        assertThat(sessions.path("items").get(0).path("memberCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("审计一个不存在的会话返回 4103，不是空列表")
    void auditingUnknownSessionIsExplicitError() {
        JsonNode body = body(get("/api/admin/sessions/99999999/messages", tokenAdmin));
        assertThat(body.path("code").asInt()).isEqualTo(4103);
    }

    // ---------------------------------------------------------------- 在线监控

    @Test
    @DisplayName("在线列表按连接数倒序，且只包含真实存在的账号")
    void onlineListReflectsRedisAndSkipsGhosts() {
        redis.opsForSet().add("im:online:" + USER_A, "sess-1", "sess-2");
        redis.opsForSet().add("im:online:" + USER_B, "sess-3");
        // 库里不存在的用户：模拟进程崩溃后残留的在线键
        redis.opsForSet().add("im:online:888888", "sess-ghost");

        JsonNode list = data(get("/api/admin/online", tokenAdmin));
        assertThat(list).hasSize(2);
        assertThat(list.get(0).path("userId").asLong()).isEqualTo(USER_A);
        assertThat(list.get(0).path("connectionCount").asLong()).isEqualTo(2L);
        assertThat(list.get(1).path("userId").asLong()).isEqualTo(USER_B);
        assertThat(list.get(1).path("connectionCount").asLong()).isEqualTo(1L);
    }

    // ---------------------------------------------------------------- 辅助

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

    private ResponseEntity<String> patch(String path, String token, Object payload) {
        // 用 PUT 而不是 PATCH：接口本身也是 PUT（见 AdminController 的说明）。
        // JDK 的 HttpURLConnection 不支持 PATCH，而 TestRestTemplate 默认就用它。
        return exchange(HttpMethod.PUT, path, token, payload);
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
