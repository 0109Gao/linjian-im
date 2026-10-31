package com.linjian.im.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * REST 侧的 JWT 认证过滤器。
 *
 * <p>为什么到 W7 才补：W6 的安全配置里写了 {@code anyRequest().authenticated()}，
 * 但当时唯一的业务接口是 {@code /api/auth/**}（permitAll），所以「没有认证过滤器」
 * 这件事不会暴露——所有真实请求都被放行了。W7 一加 {@code /api/sessions}，
 * 这个缺口就会表现为「登录成功也一律 401」。它是 W6 的遗漏，不是 W7 的新需求。</p>
 *
 * <p>解析失败时不直接返回 401，而是清空上下文后放行，让 Security 的
 * authenticationEntryPoint 统一输出符合 {@code Result} 结构的响应体。
 * 如果在过滤器里自己写响应，就会出现「某些 401 是 JSON、某些是空 body」的分裂。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTH_HEADER = "Authorization";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(AUTH_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            try {
                JwtUser user = jwtService.parse(token);
                // 角色映射成 Spring Security 的 authority，供 /api/admin/** 的
                // hasRole("ADMIN") 判断。前缀 ROLE_ 是 Spring 的约定，少写会表现为
                // 「明明是管理员却一直 403」，且没有任何日志提示原因。
                var authentication = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority(
                                user.isAdmin() ? "ROLE_ADMIN" : "ROLE_USER")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException e) {
                SecurityContextHolder.clearContext();
                log.debug("REST 请求携带的 token 无效 path={} 原因={}", request.getRequestURI(), e.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }
}
