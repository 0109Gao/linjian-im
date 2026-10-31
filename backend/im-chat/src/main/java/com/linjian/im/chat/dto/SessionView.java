package com.linjian.im.chat.dto;

import java.time.LocalDateTime;

/**
 * 会话列表项。单聊与群聊共用同一个结构，靠 {@code sessionType} 区分展示方式：
 * <ul>
 *   <li>单聊：标题取 {@code peerNickname}，{@code peerOnline} 有意义</li>
 *   <li>群聊：标题取 {@code name}，{@code peerUserId} 为 null、{@code peerOnline} 恒为 false，
 *       改用 {@code memberCount} 展示「N 人」</li>
 * </ul>
 *
 * <p>刻意不为两种会话各造一个 DTO：客户端只需要一个列表组件，分成两个类型会让
 * 前端到处写 instanceof 式的分支；而这里的差异只集中在「标题取哪个字段」一处。</p>
 *
 * <p>{@code lastMsg*} 来自 {@code im_session} 上的冗余字段，不在这里做子查询——
 * 会话列表必须按最近消息时间排序，靠子查询取最后一条会让排序无法走索引。</p>
 */
public record SessionView(
        Long sessionId,
        int sessionType,
        String name,
        Long peerUserId,
        String peerNickname,
        boolean peerOnline,
        int memberCount,
        long unreadCount,
        Long lastMsgSeq,
        LocalDateTime lastMsgAt,
        String lastMsgPreview
) {
}
