package com.linjian.im.chat.dto;

/**
 * 登录/注册成功后返回给前端的凭证。
 *
 * <p>userId 用 String 而非 Long：雪花 ID 超出 JS 安全整数范围，
 * 在 DTO 层就转成字符串，比依赖序列化器更不容易被后续改动破坏。</p>
 */
public record AuthResponse(
        String accessToken,
        String userId,
        String username,
        String nickname,
        long expireHours
) {
}
