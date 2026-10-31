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
 * <p><b>它仍然必要，即使已经有了「禁用即踢线」。</b>踢线是主动动作，依赖
 * Redis 里的在线集合与内存里的会话表；这两者都有可能对不上（进程刚重启、Redis 被清、
 * 连接在踢线的一瞬间刚好重连）。这道闸是兜底层：只要人还是禁用的，
 * 下一帧就过不去。两层的关系是「踢线让状态立刻生效，拦截器保证它无法被绕过」。</p>
 *
 * <p>拒绝方式是抛异常，Spring 会回一个 ERROR 帧并关闭会话。这是 STOMP 规范的行为
 * （收到 ERROR 后客户端应断开），不是我们主动断的。</p>
 *
 * <p>异常消息里带 {@code ACCOUNT_DISABLED} 这个英文标记，是给前端用的：
 * 它出现在 ERROR 帧的 {@code message} 头里，前端据此区分「因为被禁用而断开」
 * 与「网络抖动」——前者必须停止重连并提示，后者应当继续重连。
 * 不加标记的话，客户端只会看到一句中文提示，然后每 3 秒重连一次、每次都被拒，
 * 变成一台自己制造的 DDoS。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountStatusChannelInterceptor implements ChannelInterceptor {

    /** 与 {@code WebSocketAccountKicker} 的关闭理由保持一致，前端只认这一个字符串。 */
    static final String DISABLED_MARKER = "ACCOUNT_DISABLED";

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
            throw new MessagingException(DISABLED_MARKER + ": 账号已被禁用，连接将被关闭");
        }
        return message;
    }
}
