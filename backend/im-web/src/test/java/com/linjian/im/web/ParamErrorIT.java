package com.linjian.im.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客户端请求构造错误的处理验收。
 *
 * <p>这一组只验证一件事：<b>错在请求方的东西，不能报成服务端的错。</b></p>
 *
 * <p>起因是 W9 验收时用运行时接口扫了一遍异常路径，发现五类客户端错误——
 * 路径变量类型不对（{@code /api/sessions/abc/messages}）、缺必填参数、
 * body 不是合法 JSON、HTTP 方法用错、query 参数类型不对——<b>全部</b>落进了
 * {@code Exception} 兜底，返回 {@code 5000 服务内部错误}，同时用 ERROR 级别打了完整堆栈。</p>
 *
 * <p>它的危害不在于提示文案难看，而在于<b>日志语义被污染</b>：这些请求不构成故障，
 * 但它们产生的日志和真实故障一模一样。任何人对五个端点各发一次错请求就能刷出一屏 ERROR，
 * 真出事时报警会被淹没。<b>日志的价值在于「有一条 ERROR 就是有事」，一旦破坏这个前提，
 * 加再多日志也没用。</b></p>
 *
 * <p>这组用例存在的意义是锁住行为：将来有人重构异常处理，只要不小心删掉某个分支，
 * 这里的断言就会红——否则它会静默退化回 5000，而且退化后功能「看起来完全正常」。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ParamErrorIT {

    private static final List<String> TABLES = List.of(
            "im_inbox", "im_read_cursor", "im_message", "im_session_member", "im_session", "im_user");

    private static final long USER_A = 1001L;

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

    private String token;

    @BeforeEach
    void reset() {
        TABLES.forEach(t -> jdbc.execute("TRUNCATE TABLE " + t));
        Set<String> keys = redis.keys("im:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        jdbc.update("INSERT INTO im_user(id, username, password_hash, nickname, status, role) "
                + "VALUES (?, ?, ?, ?, 1, 0)", USER_A, "alice", "x", "爱丽丝");
        token = jwtService.issue(USER_A, "alice");
    }

    @Test
    @DisplayName("路径变量类型不对返回 4000，而不是 5000——「参数不对」和「服务挂了」是两件事")
    void pathVariableTypeMismatchIsParamInvalid() {
        JsonNode body = body(exchange(HttpMethod.GET, "/api/sessions/abc/messages", token, null));

        assertThat(body.path("code").asInt()).isEqualTo(4000);
        assertThat(body.path("message").asText()).contains("sessionId");
    }

    @Test
    @DisplayName("query 参数类型不对返回 4000")
    void queryParamTypeMismatchIsParamInvalid() {
        JsonNode body = body(exchange(HttpMethod.GET, "/api/sessions?page=abc", token, null));

        assertThat(body.path("code").asInt()).isEqualTo(4000);
    }

    @Test
    @DisplayName("缺必填 query 参数返回 4000，并指名缺的是哪个")
    void missingRequiredParamIsParamInvalid() {
        JsonNode body = body(exchange(HttpMethod.GET, "/api/users/search", token, null));

        assertThat(body.path("code").asInt()).isEqualTo(4000);
        assertThat(body.path("message").asText()).contains("q");
    }

    @Test
    @DisplayName("body 不是合法 JSON 返回 4000")
    void unreadableBodyIsParamInvalid() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        ResponseEntity<String> response = rest.exchange(rest.getRootUri() + "/api/auth/login",
                HttpMethod.POST, new HttpEntity<>("{bad json}", headers), String.class);

        assertThat(body(response).path("code").asInt()).isEqualTo(4000);
    }

    @Test
    @DisplayName("HTTP 方法用错返回真实的 405，而不是 200 + 业务码")
    void wrongMethodIs405() {
        ResponseEntity<String> response = exchange(HttpMethod.GET, "/api/auth/login", token, null);

        assertThat(response.getStatusCode().value())
                .as("协议层就没对上，不该伪装成业务失败")
                .isEqualTo(405);
        assertThat(body(response).path("code").asInt()).isEqualTo(4000);
    }

    @Test
    @DisplayName("路径不存在仍然是 404，不受本次改动影响")
    void unknownPathStays404() {
        ResponseEntity<String> response = exchange(HttpMethod.GET, "/api/not-exists", token, null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(body(response).path("code").asInt()).isEqualTo(404);
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
}
