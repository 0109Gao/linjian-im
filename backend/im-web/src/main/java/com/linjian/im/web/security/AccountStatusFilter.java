package com.linjian.im.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linjian.im.chat.entity.ImUser;
import com.linjian.im.chat.mapper.ImUserMapper;
import com.linjian.im.common.api.ErrorCode;
import com.linjian.im.common.api.Result;
import com.linjian.im.security.JwtUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 账号状态闸：把「token 合法」与「账号还能用」区分开。
 *
 * <p><b>为什么不能只靠 token。</b>token 签发后 24 小时内自证有效，但管理员随时可能
 * 禁用某个账号。若不在每次请求时查一次状态，被禁用的人可以拿着旧 token 继续用满一天——
 * 这与「禁用」这个词的预期完全不符。</p>
 *
 * <p><b>为什么不把 status 写进 token。</b>那样 status 就被冻结在签发时刻，
 * 禁用一个在线用户要等 24 小时才生效，比不写还糟。role 能进 token 而 status 不能，
 * 区别在于：role 在会话期间不变（改角色可要求重新登录），status 必须实时生效。</p>
 *
 * <p><b>成本是一次主键查询。</b>{@code selectById} 走聚簇索引，实测在同一台机器上是
 * 几十微秒级；相比之下，IM 的 REST 接口本身就要查会话表、成员表、消息表，
 * 多这一次查询不改变量级。如果将来它成为瓶颈，正确的做法是加「账号状态版本号」，
 * 而不是给状态加一个会过期的缓存。</p>
 *
 * <p>放在 im-web 而不是 im-security：校验需要访问 {@code im_user} 表，而
 * {@code im-chat} 依赖 {@code im-security}（JwtService 在 security 里），
 * 反向依赖会成环。让 security 只管「令牌是否可信」，让 web 层管「账号是否可用」，
 * 边界也更干净。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountStatusFilter extends OncePerRequestFilter {

    private final ImUserMapper userMapper;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof JwtUser jwtUser) {
            ImUser user = userMapper.selectById(jwtUser.userId());
            if (user == null || user.getStatus() == null || user.getStatus() != 1) {
                SecurityContextHolder.clearContext();
                log.info("拒绝已禁用账号的请求 userId={} path={}", jwtUser.userId(), request.getRequestURI());
                // 用 403 而不是 401：401 的语义是「不知道你是谁」，会让前端清掉 token 直接跳登录页，
                // 用户看不到「你被禁用了」这句话。403 + 专属错误码能让前端把原因显示出来。
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(
                        Result.error(ErrorCode.ACCOUNT_DISABLED)));
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
