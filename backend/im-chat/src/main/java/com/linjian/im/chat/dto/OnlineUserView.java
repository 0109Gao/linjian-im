package com.linjian.im.chat.dto;

/**
 * 在线用户行。
 *
 * <p>{@code connectionCount} 是这个人当前有几条活连接（手机 + 电脑就是 2），
 * 不是「在线人数」——后者在概览里按用户数算。这两个数字不一样，
 * 放在一起时最容易出现「概览说 10 人在线，列表数出 13 条连接」这种看起来像 bug 的差异。</p>
 */
public record OnlineUserView(
        Long userId,
        String username,
        String nickname,
        long connectionCount
) {
}
