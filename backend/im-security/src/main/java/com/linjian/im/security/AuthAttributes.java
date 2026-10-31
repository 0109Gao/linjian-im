package com.linjian.im.security;

/**
 * 握手阶段写入 WebSocketSession 属性的键名。CONNECT 阶段若需要回溯握手结果，
 * 从这里取，避免重复解析 token。
 */
public final class AuthAttributes {

    public static final String USER_ID = "im.auth.userId";
    public static final String USERNAME = "im.auth.username";

    private AuthAttributes() {
    }
}
