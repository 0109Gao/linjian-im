package com.linjian.im.web.ws;

import com.linjian.im.chat.entity.ImUser;
import com.linjian.im.chat.mapper.ImUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * STOMP 侧的账号状态闸。与 {@code AccountStatusFilter} 同一个目的，只是换了一条链路。
 *
 * <p>WebSocket 不经过 Servlet 过滤器链，所以 REST 侧的 {@code AccountStatusFilter}
 * 对已建立的连接完全无效。一个被禁用的人只要不断线，就能一直发消息——
 * 这是「禁用」功能里最容易被漏掉的一半。</p>
 *
 * <p>检查时机选 CONNECT / SEND / SUBSCRIBE 三种帧：</p>
 * <ul>
 *   <li>CONNECT：断线重连的人不该重新进来</li>
 *   <li>SEND：已连接的人不该继续发消息</li>
 *   <li>SUBSCRIBE：已连接的人不该继续订阅新会话</li>
 * </ul>
 *
 * <p><b>不做「主动踢线」。</b>禁用时不去遍历在线连接把它断开，而是等它下一次发帧时拒绝。
 * 代价是被禁用的用户可能仍然收得到别人的消息（直到他发帧被发现），换来的是
 * 管理操作不依赖「在线连接表」这个额外状态——那张表一旦漏记一次，就会出现
 * 「禁用了但没踢掉、且怎么都查不出为什么」。</p>
 *
 * <p>拒绝方式是抛异常，Spring 会回一个 ERROR 帧并关闭会话。这是 STOMP 规范的行为
 * （收到 ERROR 后客户端应断开），不是我们主动断的。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountStatusChannelInterceptor implements ChannelInterceptor {

    private final ImUserMapper userMapper;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command != StompCommand.CONNECT
                && command != StompCommand.SEND
                && command != StompCommand.SUBSCRIBE) {
            return message;
        }

        Principal principal = accessor.getUser();
        if (principal == null) {
            // CONNECT 阶段 Principal 应由 StompAuthChannelInterceptor 绑定好。
            // 到这里还是空，说明拦截器顺序被改坏了——宁可拒绝，也不要放一条没有身份的连接进来。
            if (command == StompCommand.CONNECT) {
                throw new MessagingException("CONNECT 帧未绑定用户身份，拒绝连接");
            }
            // SEND / SUBSCRIBE 无身份时会由 Spring 自己拒绝（未认证），这里放行即可。
            return message;
        }

        long userId;
        try {
            userId = Long.parseLong(principal.getName());
        } catch (NumberFormatException e) {
            throw new MessagingException("会话身份非法：" + principal.getName(), e);
        }

        ImUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            log.info("拒绝已禁用账号的 STOMP 帧 command={} userId={} sessionId={}",
                    command, userId, accessor.getSessionId());
            throw new MessagingException("账号已被禁用，连接将被关闭");
        }
        return message;
    }
}
