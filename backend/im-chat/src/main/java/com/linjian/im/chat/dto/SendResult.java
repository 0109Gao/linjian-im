package com.linjian.im.chat.dto;

import java.time.LocalDateTime;

/**
 * 发送结果，投递到发送者的 {@code /user/queue/ack}。
 *
 * <p>{@code duplicated} 为 true 表示这是客户端重发、服务端回放了首次落库的结果，
 * 此时 {@code seq} 与 {@code messageId} 都是<b>首次</b>的，不是新分配的。
 * 客户端必须用这个 seq 覆盖本地那条「发送中」的消息，否则它会永远停在发送中，
 * 下次拉取时同一条内容会再出现一次。</p>
 *
 * <p>{@code error} 非空表示这次没发出去（内容非法、不是会话成员等）。
 * 之所以不抛异常让 Spring 回 ERROR 帧，是因为 ERROR 帧会关闭整个会话——
 * 一条消息不合法就把用户的连接踢掉，代价远高于收益。</p>
 */
public record SendResult(
        boolean success,
        boolean duplicated,
        Long messageId,
        Long seq,
        LocalDateTime createdAt,
        String error
) {

    public static SendResult ok(MessageView view, boolean duplicated) {
        return new SendResult(true, duplicated, view.id(), view.seq(), view.createdAt(), null);
    }

    public static SendResult fail(String error) {
        return new SendResult(false, false, null, null, null, error);
    }
}
