package com.linjian.im.web.ws;

import com.linjian.im.chat.dto.MessageView;
import com.linjian.im.chat.dto.ReadResult;
import com.linjian.im.chat.service.MessageDelivery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 把领域层的「该通知谁」翻译成 STOMP 目的地。
 *
 * <p>三个目的地各有分工：</p>
 * <ul>
 *   <li>{@code /user/queue/message}：单聊点对点。收件人由 Principal 决定，
 *       SimpleBroker 对 user destination 做会话隔离，不需要额外的 ACL。</li>
 *   <li>{@code /topic/session.{id}}：群聊广播。SimpleBroker 对 {@code /topic}
 *       <b>不做任何权限检查</b>，任何登录用户只要知道 sessionId 就能订阅别人的群——
 *       所以订阅必须由 {@link GroupTopicAuthInterceptor} 逐个校验成员身份，
 *       这两者是一套的，改一个必须改另一个。</li>
 *   <li>{@code /user/queue/read}：已读事件。推给本人所有连接，让其他端同步清红点。</li>
 * </ul>
 *
 * <p>Spring 会把同一个 userId 的多条 session 聚合到同一个 user destination 上，
 * 因此用户两端在线时两端都会收到——多端推送在点对点路径上是免费的。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompMessageDelivery implements MessageDelivery {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void deliverToUser(Long userId, MessageView message) {
        try {
            messagingTemplate.convertAndSendToUser(String.valueOf(userId), "/queue/message", message);
        } catch (RuntimeException e) {
            // 推送失败不影响消息已经落库：客户端下次拉取时会用 seq 补齐。
            // 这里只记日志，不向上抛——一个接收者推送失败不应该让整次发送失败。
            log.warn("消息推送失败 userId={} sessionId={} seq={} 原因={}",
                    userId, message.sessionId(), message.seq(), e.getMessage());
        }
    }

    @Override
    public void deliverToSession(Long sessionId, MessageView message) {
        try {
            messagingTemplate.convertAndSend(WsDestinations.sessionTopic(sessionId), message);
        } catch (RuntimeException e) {
            // 广播失败同样只记日志：群消息已经落库，成员下次拉取能补回。
            log.warn("群消息广播失败 sessionId={} seq={} 原因={}",
                    sessionId, message.seq(), e.getMessage());
        }
    }

    @Override
    public void deliverReadToUser(Long userId, ReadResult readResult) {
        try {
            messagingTemplate.convertAndSendToUser(String.valueOf(userId), "/queue/read", readResult);
        } catch (RuntimeException e) {
            // 已读同步失败只会让别的端多显示一会儿红点，下次拉取即恢复。
            log.warn("已读事件推送失败 userId={} sessionId={} 原因={}",
                    userId, readResult.sessionId(), e.getMessage());
        }
    }
}
