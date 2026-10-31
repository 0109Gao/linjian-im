package com.linjian.im.web.ws;

import com.linjian.im.chat.dto.MessageView;
import com.linjian.im.chat.service.MessageDelivery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 把领域层的「该通知谁」翻译成 STOMP 的私有投递。
 *
 * <p>目的地是 {@code /user/queue/message}，不是会话主题。SimpleBroker 对
 * {@code /topic} 的订阅不做任何 ACL，用它推私聊意味着「任何登录用户只要拿到
 * 一个 sessionId 就能订阅别人的会话」；而 user destination 由 Principal 决定收件人，
 * 结构上不存在漏判。</p>
 *
 * <p>Spring 会把同一个 userId 的多条 session 聚合到同一个 user destination 上，
 * 因此用户两端在线时两端都会收到——多端推送在这里是免费的，不需要额外遍历。</p>
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
}
