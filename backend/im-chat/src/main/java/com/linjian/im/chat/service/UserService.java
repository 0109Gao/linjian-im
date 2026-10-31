package com.linjian.im.chat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linjian.im.chat.dto.AuthResponse;
import com.linjian.im.chat.dto.LoginRequest;
import com.linjian.im.chat.dto.RegisterRequest;
import com.linjian.im.chat.entity.ImUser;
import com.linjian.im.chat.mapper.ImUserMapper;
import com.linjian.im.common.api.ErrorCode;
import com.linjian.im.common.exception.BusinessException;
import com.linjian.im.common.id.SnowflakeIdGenerator;
import com.linjian.im.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final ImUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * 用于「用户不存在」分支的假哈希。目的是让该分支也走一次 BCrypt 比对，
     * 使响应耗时与「用户存在但密码错」接近，避免通过响应时间枚举出哪些用户名已被注册。
     *
     * <p>懒初始化而非 {@code @PostConstruct}：单元测试直接 new 出该服务时不经过容器生命周期，
     * 若依赖容器回调会让测量分支的测试无法构造对象。</p>
     */
    private volatile String dummyHash;

    private String dummyHash() {
        String local = dummyHash;
        if (local == null) {
            synchronized (this) {
                local = dummyHash;
                if (local == null) {
                    local = passwordEncoder.encode("never-matches-" + UUID.randomUUID());
                    dummyHash = local;
                }
            }
        }
        return local;
    }

    public AuthResponse login(LoginRequest request) {
        ImUser user = findByUsername(request.getUsername());
        String hash = (user != null) ? user.getPasswordHash() : dummyHash();
        boolean matched = passwordEncoder.matches(request.getPassword(), hash);

        // 用户不存在与密码错误返回同一个错误码，理由同上：不给枚举留信号。
        if (user == null || !matched) {
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS);
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS, "账号已被禁用");
        }
        return toAuthResponse(user);
    }

    @Transactional(rollbackFor = Exception.class)
    public AuthResponse register(RegisterRequest request) {
        if (findByUsername(request.getUsername()) != null) {
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }

        ImUser user = new ImUser();
        user.setId(idGenerator.nextId());
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname() == null || request.getNickname().isBlank()
                ? request.getUsername()
                : request.getNickname().trim());
        user.setStatus(1);
        user.setCreatedAt(LocalDateTime.now());
        userMapper.insert(user);

        log.info("新用户注册 userId={} username={}", user.getId(), user.getUsername());
        return toAuthResponse(user);
    }

    private ImUser findByUsername(String username) {
        return userMapper.selectOne(Wrappers.<ImUser>lambdaQuery()
                .eq(ImUser::getUsername, username)
                .last("limit 1"));
    }

    private AuthResponse toAuthResponse(ImUser user) {
        String token = jwtService.issue(user.getId(), user.getUsername());
        return new AuthResponse(
                token,
                String.valueOf(user.getId()),
                user.getUsername(),
                user.getNickname(),
                jwtService.getExpireHours()
        );
    }
}
