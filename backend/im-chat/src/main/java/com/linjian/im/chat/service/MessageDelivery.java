package com.linjian.im.chat.service;

import com.linjian.im.chat.dto.MessageView;

/**
 * 消息投递端口。
 *
 * <p>定义在 {@code im-chat}、实现在 {@code im-web}，是为了守住分层规则：
 * 领域层不该知道 STOMP 的存在。领域层只回答「这条消息该通知给谁」，
 * 「怎么通知」是协议适配层的事。</p>
 *
 * <p>为什么用私有投递（{@code /user/queue/message}）而不是会话主题
 * （{@code /topic/session.{id}}）：SimpleBroker 对 {@code /topic} 的订阅不做任何 ACL，
 * 任何已登录用户只要拿到一个 sessionId 就能订阅别人的会话。用 user destination
 * 把「谁能收到」交给 Principal 决定，结构上就不存在漏判；同时 Spring 的
 * user destination 本来就按 userId 聚合 session，多端在线会自动各收一份。</p>
 */
public interface MessageDelivery {

    void deliverToUser(Long userId, MessageView message);
}
