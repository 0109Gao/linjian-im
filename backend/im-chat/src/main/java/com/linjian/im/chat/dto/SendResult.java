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
 *
 * <p><b>为什么要带 {@code clientMsgId}（W9 补）：</b>前端会先在本地插一条
 * 「发送中」的消息再发出去，收到 ack 后要把它改成「已发送」。如果 ack 里只有 seq，
 * 客户端没有任何办法知道这个 seq 对应哪一条本地消息——用户连发三条时，
 * 三个 ack 会依次到达，靠到达顺序匹配在丢包或乱序时必然错位，
 * 表现是「一条消息显示已发送、另一条永远转圈」。带上客户端自己生成的
 * clientMsgId，匹配就变成精确查找，与顺序无关。</p>
 */
public record SendResult(
        boolean success,
        boolean duplicated,
        Long messageId,
        Long seq,
        String clientMsgId,
        LocalDateTime createdAt,
        String error
) {

    public static SendResult ok(MessageView view, boolean duplicated) {
        return new SendResult(true, duplicated, view.id(), view.seq(),
                view.clientMsgId(), view.createdAt(), null);
    }

    /**
     * 失败也要把 clientMsgId 原样带回：客户端靠它把那条本地消息标成「发送失败」
     * 并允许重试。不带的话，失败的请求在界面上没有任何落点，用户只会看到
     * 「点了发送但什么都没发生」。
     */
    public static SendResult fail(String clientMsgId, String error) {
        return new SendResult(false, false, null, null, clientMsgId, null, error);
    }
}
