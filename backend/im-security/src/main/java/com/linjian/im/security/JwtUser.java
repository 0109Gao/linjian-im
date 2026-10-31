package com.linjian.im.security;

/**
 * 从 token 中解出的最小用户身份。
 *
 * <p>W9 增加了 {@code role}：管理端需要一个「这个 token 能不能进后台」的判断依据，
 * 而这个判断必须在服务端完成——前端路由守卫只影响体验，改一行 JS 就能绕过。</p>
 *
 * <p>role 放进 token 而不是每次查库，是因为它在会话期间不变（改角色需重新登录才生效）。
 * 这与 {@code status}（禁用）不同：status 随时可能被管理员改动，因此
 * {@code AccountStatusFilter} 每次请求都查库校验，不写进 token。</p>
 *
 * @param userId   用户 ID
 * @param username 登录名
 * @param role     0 普通用户 / 1 管理员。旧 token 无该 claim 时按 0 处理（fail-closed）
 */
public record JwtUser(long userId, String username, int role) {

    public static final int ROLE_USER = 0;
    public static final int ROLE_ADMIN = 1;

    public boolean isAdmin() {
        return role == ROLE_ADMIN;
    }
}
