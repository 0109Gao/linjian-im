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
 *
 * <p><b>为什么要带 {@code clientMsgId}（W8 加）：</b>多端场景下，发送者自己也会收到
 * 这条消息的推送。客户端此时手里已经有一条「本地乐观插入」的同内容消息，
 * 唯一能把两者认成同一条的键就是 clientMsgId——它由客户端生成、重发保持不变。
 * 没有它，客户端只能按内容猜，重复发送两条相同文本时就会错删。
 * 对接收方而言这只是一个不透明的随机串，不泄露任何信息。</p>
 */
public record MessageView(
        Long id,
        Long sessionId,
        Long seq,
        Long senderId,
        String clientMsgId,
        String content,
        LocalDateTime createdAt
) {

    public static MessageView of(ImMessage m) {
        return new MessageView(m.getId(), m.getSessionId(), m.getSeq(),
                m.getSenderId(), m.getClientMsgId(), m.getContent(), m.getCreatedAt());
    }
}
