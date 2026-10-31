package com.linjian.im.web.ws;

import com.linjian.im.chat.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.Map;

/**
 * 维护在线状态。
 *
 * <p>只监听连接与断开两个事件——SimpleBroker 不为心跳暴露事件，因此这里拿不到
 * 「这条连接还活着」的信号。续期由 {@code MaintenanceJob} 定时给在线键刷 TTL 完成。</p>
 *
 * <p>断开事件里的 Principal 不保证存在：连接可能在 CONNECT 之前就被关掉
 * （例如握手成功但客户端直接关闭 TCP）。那种情况不该往 Redis 里写任何东西，
 * 所以这里判空后静默跳过，而不是抛异常。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PresenceEventListener {

    private static final String ATTR_USER_ID = "im.userId";

    private final PresenceService presenceService;

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal user = accessor.getUser();
        String sessionId = accessor.getSessionId();
        if (user == null || sessionId == null) {
            return;
        }
        Long userId = parse(user.getName());
        if (userId == null) {
            return;
        }
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes != null) {
            // 存进会话属性，断开时即使 Principal 丢了也能知道是谁
            attributes.put(ATTR_USER_ID, userId);
        }
        presenceService.online(userId, sessionId);
        log.debug("上线 userId={} sessionId={}", userId, sessionId);
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = event.getSessionId();

        Long userId = null;
        Principal user = accessor.getUser();
        if (user != null) {
            userId = parse(user.getName());
        }
        if (userId == null) {
            Map<String, Object> attributes = accessor.getSessionAttributes();
            if (attributes != null && attributes.get(ATTR_USER_ID) instanceof Long stored) {
                userId = stored;
            }
        }
        if (userId == null || sessionId == null) {
            log.debug("连接断开但无法确定用户，跳过在线状态清理 sessionId={}", sessionId);
            return;
        }
        presenceService.offline(userId, sessionId);
        log.debug("下线 userId={} sessionId={}", userId, sessionId);
    }

    private Long parse(String raw) {
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            log.warn("Principal 名称不是合法的 userId: {}", raw);
            return null;
        }
    }
}
