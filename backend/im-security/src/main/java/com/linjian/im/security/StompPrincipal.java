package com.linjian.im.security;

import java.security.Principal;

/**
 * STOMP 会话的 Principal。用 record 包一层而不是直接 {@code () -> String.valueOf(userId)}，
 * 是为了让日志与调试时能看见类型，而不是看到一个匿名 lambda。
 */
public record StompPrincipal(long userId) implements Principal {

    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}
