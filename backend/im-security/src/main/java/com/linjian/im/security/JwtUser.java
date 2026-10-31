package com.linjian.im.security;

/**
 * 从 token 中解出的最小用户身份。不携带密码、角色等额外信息，
 * 保持 token 体积小（每次握手与每次重连都要传）。
 */
public record JwtUser(long userId, String username) {
}
