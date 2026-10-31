package com.linjian.im.chat.service;

import com.linjian.im.chat.dto.AuthResponse;
import com.linjian.im.chat.dto.LoginRequest;
import com.linjian.im.chat.dto.RegisterRequest;
import com.linjian.im.chat.entity.ImUser;
import com.linjian.im.chat.mapper.ImUserMapper;
import com.linjian.im.common.api.ErrorCode;
import com.linjian.im.common.exception.BusinessException;
import com.linjian.im.common.id.SnowflakeIdGenerator;
import com.linjian.im.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账号领域逻辑单测。
 *
 * <p>按 W6 的约定，这一层不连数据库、不连 Redis，全部用 Mockito 替身，
 * 以便在 CI 里零依赖运行。</p>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private ImUserMapper userMapper;
    @Mock
    private JwtService jwtService;
    @Mock
    private SnowflakeIdGenerator idGenerator;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(10);

    private UserService userService;

    private ImUser existingUser;

    @BeforeEach
    void setUp() {
        userService = new UserService(userMapper, encoder, jwtService, idGenerator);

        existingUser = new ImUser();
        existingUser.setId(109859320094527488L);
        existingUser.setUsername("linjian");
        existingUser.setPasswordHash(encoder.encode("123456"));
        existingUser.setNickname("林间");
        existingUser.setStatus(1);
    }

    @Test
    @DisplayName("密码正确时应签发 token，且返回的 userId 为字符串")
    void login_success() {
        when(userMapper.selectOne(any())).thenReturn(existingUser);
        when(jwtService.issue(anyLong(), anyString())).thenReturn("token-abc");
        when(jwtService.getExpireHours()).thenReturn(24L);

        LoginRequest req = new LoginRequest();
        req.setUsername("linjian");
        req.setPassword("123456");

        AuthResponse resp = userService.login(req);

        assertThat(resp.accessToken()).isEqualTo("token-abc");
        assertThat(resp.userId()).isEqualTo("109859320094527488");
        assertThat(resp.nickname()).isEqualTo("林间");
        verify(jwtService).issue(109859320094527488L, "linjian");
    }

    @Test
    @DisplayName("密码错误与用户不存在返回同一错误码，不泄露账号是否已注册")
    void login_wrongPassword_and_unknownUser_shareSameCode() {
        when(userMapper.selectOne(any())).thenReturn(existingUser, (ImUser) null);

        LoginRequest wrongPwd = new LoginRequest();
        wrongPwd.setUsername("linjian");
        wrongPwd.setPassword("000000");

        assertThatThrownBy(() -> userService.login(wrongPwd))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_CREDENTIALS);

        LoginRequest unknown = new LoginRequest();
        unknown.setUsername("nobody");
        unknown.setPassword("123456");

        assertThatThrownBy(() -> userService.login(unknown))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_CREDENTIALS);

        verify(jwtService, never()).issue(anyLong(), anyString());
    }

    @Test
    @DisplayName("用户不存在时仍要执行一次 BCrypt 比对，否则可用响应耗时枚举用户名")
    void login_unknownUser_stillRunsBcrypt() {
        when(userMapper.selectOne(any())).thenReturn(null);

        LoginRequest req = new LoginRequest();
        req.setUsername("nobody");
        req.setPassword("123456");

        long start = System.nanoTime();
        assertThatThrownBy(() -> userService.login(req)).isInstanceOf(BusinessException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // cost=10 的单次比对通常在 40ms 以上；若走的是「短路返回」分支，耗时会是个位数毫秒。
        assertThat(elapsedMs)
                .as("用户不存在分支的耗时 %dms 过短，说明没有执行 BCrypt 比对", elapsedMs)
                .isGreaterThan(20L);
    }

    @Test
    @DisplayName("账号被禁用时不允许登录")
    void login_disabledAccount_rejected() {
        existingUser.setStatus(0);
        when(userMapper.selectOne(any())).thenReturn(existingUser);

        LoginRequest req = new LoginRequest();
        req.setUsername("linjian");
        req.setPassword("123456");

        assertThatThrownBy(() -> userService.login(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("禁用");
    }

    @Test
    @DisplayName("用户名重复时注册被拒绝")
    void register_duplicateUsername_rejected() {
        when(userMapper.selectOne(any())).thenReturn(existingUser);

        RegisterRequest req = new RegisterRequest();
        req.setUsername("linjian");
        req.setPassword("123456");

        assertThatThrownBy(() -> userService.register(req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USERNAME_TAKEN);

        verify(userMapper, never()).insert(any());
    }

    @Test
    @DisplayName("注册成功时应写入哈希而非明文，并自动签发 token")
    void register_success_storesHashNotPlaintext() {
        when(userMapper.selectOne(any())).thenReturn(null);
        when(idGenerator.nextId()).thenReturn(109859320094527490L);
        when(jwtService.issue(anyLong(), anyString())).thenReturn("token-new");
        when(jwtService.getExpireHours()).thenReturn(24L);

        RegisterRequest req = new RegisterRequest();
        req.setUsername("newcomer");
        req.setPassword("abc123456");

        AuthResponse resp = userService.register(req);

        ArgumentCaptor<ImUser> captor = ArgumentCaptor.forClass(ImUser.class);
        verify(userMapper).insert(captor.capture());
        ImUser saved = captor.getValue();

        assertThat(saved.getPasswordHash()).isNotEqualTo("abc123456").startsWith("$2a$");
        assertThat(encoder.matches("abc123456", saved.getPasswordHash())).isTrue();
        assertThat(saved.getNickname()).isEqualTo("newcomer");
        assertThat(saved.getStatus()).isEqualTo(1);
        assertThat(resp.accessToken()).isEqualTo("token-new");
    }
}
