package com.linjian.im.security;

import com.linjian.im.common.api.ErrorCode;
import com.linjian.im.common.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 取当前登录用户。
 *
 * <p>放在 im-security 而不是 im-web，是因为它依赖 SecurityContext，
 * 属于认证能力的一部分；im-web 的 Controller 只调用它，不自己碰上下文。</p>
 *
 * <p>取不到时抛 {@link ErrorCode#UNAUTHORIZED}。正常情况下走不到这一步——
 * Security 的 {@code authenticated()} 已经把匿名请求挡在过滤器链里了。
 * 这个兜底是为了防止将来有人在 SecurityConfig 里放行了某个新路径，
 * 却忘了在方法里做身份判断。</p>
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Long id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof JwtUser user)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return user.userId();
    }
}
