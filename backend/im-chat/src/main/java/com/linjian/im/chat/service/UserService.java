package com.linjian.im.chat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linjian.im.chat.dto.AuthResponse;
import com.linjian.im.chat.dto.LoginRequest;
import com.linjian.im.chat.dto.RegisterRequest;
import com.linjian.im.chat.dto.UserBrief;
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
import java.util.List;
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

    /**
     * 按用户名或昵称搜索联系人。
     *
     * <p>W7 必须有这个接口：没有它，客户端拿到一个空的会话列表就无法发起第一个会话——
     * 用户不可能凭空知道对方的 userId。这也是为什么它和「会话列表」一起被列进 W7
     * 而不是留到前端阶段。</p>
     *
     * <p>只返回 status=1 的账号，且排除自己。limit 由服务端算成 int 后拼进 SQL，
     * 不接受客户端传原始值，避免把分页参数变成注入面。</p>
     */
    public List<UserBrief> search(String keyword, Long selfId, int limit) {
        String trimmed = keyword == null ? "" : keyword.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        // 用 final 变量给 lambda 捕获：前面若对 q 重新赋值，q 就不再是 effectively final，
        // 编译期会直接拒绝。
        final String q = trimmed.length() > 20 ? trimmed.substring(0, 20) : trimmed;
        int safeLimit = Math.min(Math.max(limit, 1), 30);
        List<ImUser> users = userMapper.selectList(Wrappers.<ImUser>lambdaQuery()
                .eq(ImUser::getStatus, 1)
                .ne(ImUser::getId, selfId)
                .and(w -> w.like(ImUser::getUsername, q).or().like(ImUser::getNickname, q))
                .last("limit " + safeLimit));
        return users.stream()
                .map(u -> new UserBrief(u.getId(), u.getUsername(), u.getNickname()))
                .toList();
    }

    /** 取单个用户的对外信息，不存在返回 null（调用方自行决定是否当作错误）。 */
    public UserBrief findBrief(Long id) {
        ImUser user = userMapper.selectById(id);
        return user == null ? null : new UserBrief(user.getId(), user.getUsername(), user.getNickname());
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
