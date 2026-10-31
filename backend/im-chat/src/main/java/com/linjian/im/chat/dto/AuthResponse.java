package com.linjian.im.chat.dto;

/**
 * 登录/注册成功后返回给前端的凭证。
 *
 * <p>userId 用 String 而非 Long：雪花 ID 超出 JS 安全整数范围，
 * 在 DTO 层就转成字符串，比依赖序列化器更不容易被后续改动破坏。</p>
 *
 * <p>{@code role} 下发是给前端做路由分流的：管理员登录后进 5176，普通用户进 5175。
 * 它不是权限本身——真正的拦截在服务端 {@code /api/admin/**} 上。</p>
 */
public record AuthResponse(
        String accessToken,
        String userId,
        String username,
        String nickname,
        int role,
        long expireHours
) {
}
