package com.linjian.im.chat.dto;

import java.time.LocalDateTime;

/**
 * 会话列表项。
 *
 * <p>{@code lastMsg*} 来自 {@code im_session} 上的冗余字段，不在这里做子查询——
 * 会话列表必须按最近消息时间排序，靠子查询取最后一条会让排序无法走索引。</p>
 *
 * <p>{@code peerNickname} 在群聊场景会变成群名，W7 只有单聊，因此语义固定为「对方昵称」。</p>
 */
public record SessionView(
        Long sessionId,
        Long peerUserId,
        String peerNickname,
        boolean peerOnline,
        long unreadCount,
        Long lastMsgSeq,
        LocalDateTime lastMsgAt,
        String lastMsgPreview
) {
}
