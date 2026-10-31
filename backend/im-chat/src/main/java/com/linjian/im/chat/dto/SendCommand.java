package com.linjian.im.chat.dto;

/**
 * 客户端发消息的入参（STOMP {@code /app/chat.send} 的 payload）。
 *
 * <p>{@code clientMsgId} 由客户端生成，重发时必须保持不变——服务端靠
 * {@code (sender_id, client_msg_id)} 的唯一索引识别重发并返回首次的 seq。</p>
 */
public record SendCommand(
        Long sessionId,
        String clientMsgId,
        String content
) {
}
