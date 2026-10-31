package com.linjian.im.chat.dto;

import com.linjian.im.chat.entity.ImMessage;

import java.time.LocalDateTime;

/**
 * 消息对外视图。REST 拉取与 STOMP 推送共用同一个结构，避免出现
 * 「接口返回的字段和推送的字段不一样」这种前端必须写两套解析的情况。
 *
 * <p>所有 Long 字段（id / sessionId / senderId / seq）都交给
 * {@code LongSafeSerializer} 处理：数值超出 JS 安全整数上限时输出字符串，
 * 未超出时保持数字。客户端因此应当把 ID 一律当字符串处理。</p>
 */
public record MessageView(
        Long id,
        Long sessionId,
        Long seq,
        Long senderId,
        String content,
        LocalDateTime createdAt
) {

    public static MessageView of(ImMessage m) {
        return new MessageView(m.getId(), m.getSessionId(), m.getSeq(),
                m.getSenderId(), m.getContent(), m.getCreatedAt());
    }
}
