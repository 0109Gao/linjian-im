package com.linjian.im.web.ws;

import com.linjian.im.chat.service.SessionService;
import com.linjian.im.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * 第三道闸：订阅群主题时校验成员身份。
 *
 * <p><b>为什么需要它：</b>单聊走 user destination，收件人由 Principal 决定，
 * 结构上不可能订阅到别人的私聊。但群聊必须走 {@code /topic}——广播一次的成本
 * 与人数无关，逐个推 200 人才是 200 次序列化。代价是 SimpleBroker 对
 * {@code /topic} 的订阅<b>不做任何 ACL</b>：任何登录用户只要知道 sessionId，
 * 一条 SUBSCRIBE 帧就能收走这个群的全部后续消息。这个拦截器就是那条缺失的 ACL。</p>
 *
 * <p><b>为什么返回 null 而不是抛异常：</b>抛 {@code MessagingException} 会让 Spring
 * 回一个 ERROR 帧并<b>关闭整条 STOMP 会话</b>——用户拿一个错误 id 试一下就掉线，
 * 这不是我们要的惩罚强度，也会让正常的重连逻辑误判成服务端故障。
 * 返回 null 表示「这一帧不往下发」，订阅不会被登记，客户端只是收不到消息。</p>
 *
 * <p><b>已知窗口：</b>订阅校验是「订阅那一刻」的一次性判断。用户订阅后被踢出群，
 * 已登记的订阅不会自动失效，他仍能收到后续广播。要堵这个洞得在推送时按订阅者
 * 逐个校验（等于把广播退回成逐个推），或者维护订阅关系并在踢人时主动断订阅。
 * W8 没有踢人功能，因此这个窗口在当前功能集里不可达；做踢人时必须先解决它。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupTopicAuthInterceptor implements ChannelInterceptor {

    private final SessionService sessionService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return message;
        }

        Long sessionId = WsDestinations.parseSessionId(accessor.getDestination());
        if (sessionId == null) {
            // 不是群主题（/user/queue/** 由 broker 的 Principal 隔离保证安全），放行
            return message;
        }

        Principal principal = accessor.getUser();
        if (principal == null) {
            log.warn("订阅被拒绝：帧上没有 Principal destination={}", accessor.getDestination());
            return null;
        }

        Long userId;
        try {
            userId = Long.valueOf(principal.getName());
        } catch (NumberFormatException e) {
            log.warn("订阅被拒绝：Principal 不是用户 id principal={}", principal.getName());
            return null;
        }

        try {
            sessionService.assertMember(sessionId, userId);
            log.debug("订阅群主题通过 userId={} sessionId={}", userId, sessionId);
            return message;
        } catch (BusinessException e) {
            log.warn("订阅群主题被拒绝 userId={} sessionId={} 原因={}", userId, sessionId, e.getMessage());
            return null;
        }
    }
}
