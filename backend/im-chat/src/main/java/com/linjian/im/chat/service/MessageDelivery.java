package com.linjian.im.chat.service;

import com.linjian.im.chat.dto.MessageView;
import com.linjian.im.chat.dto.ReadResult;

/**
 * 消息投递端口。
 *
 * <p>定义在 {@code im-chat}、实现在 {@code im-web}，是为了守住分层规则：
 * 领域层不该知道 STOMP 的存在。领域层只回答「这条消息该通知给谁」，
 * 「怎么通知」是协议适配层的事。</p>
 *
 * <p>三个方法对应两种会话、两类事件，选择逻辑全部在领域层，适配层不做判断：</p>
 * <ul>
 *   <li>{@link #deliverToUser}：点对点推给某个人的全部连接。单聊用它——
 *       收件人由 Principal 决定，结构上不存在「猜中 sessionId 就偷听」的可能。</li>
 *   <li>{@link #deliverToSession}：向会话主题广播一次。群聊用它——
 *       200 人群逐个推是 200 次序列化加 200 次在线判断，广播是一次。
 *       代价是订阅必须自己校验成员身份，见 {@code GroupTopicAuthInterceptor}。</li>
 *   <li>{@link #deliverReadToUser}：已读事件推给本人其他连接。已读游标是按人存的，
 *       一端上报后其他端的红点要一起消失，靠这条事件同步。</li>
 * </ul>
 */
public interface MessageDelivery {

    void deliverToUser(Long userId, MessageView message);

    void deliverToSession(Long sessionId, MessageView message);

    void deliverReadToUser(Long userId, ReadResult readResult);
}
