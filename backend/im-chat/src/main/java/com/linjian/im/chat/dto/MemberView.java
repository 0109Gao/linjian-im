package com.linjian.im.chat.dto;

import java.time.LocalDateTime;

/** 群成员列表项。{@code online} 与单聊会话列表里的同名含义一致：该用户当前有活连接。 */
public record MemberView(
        Long userId,
        String nickname,
        boolean online,
        LocalDateTime joinedAt
) {
}
