package com.linjian.im.web.ws;

import com.linjian.im.chat.service.AccountKicker;
import com.linjian.im.chat.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import java.util.Set;

/**
 * 用 WebSocket 会话表实现「立刻踢下线」。
 *
 * <p>依据是 Redis 里的 {@code im:online:{userId}} 集合：它记着这个人所有活连接的 sessionId。
 * 逐个查出 {@link org.springframework.web.socket.WebSocketSession} 并关闭，
 * 关闭码用 <b>1008（Policy Violation）</b>——不用 1000 正常关闭，
 * 因为客户端（和我们自己的前端）需要区分「用户主动退出」与「被服务端终止」，
 * 前者不该弹提示，后者必须说明原因并停止重连。</p>
 *
 * <p>关闭理由写 {@code ACCOUNT_DISABLED}：浏览器会把关闭帧的 reason 交给 JS
 * （{@code CloseEvent.reason}），前端据此显示「账号已被禁用」并停止 3 秒一轮的重连。
 * 不带这个标记的话，STOMP 客户端只会看到「连接被关闭」，然后按 {@code reconnectDelay}
 * 无限重连，每次重连都在 CONNECT 阶段被拒——从用户视角看是「一直在重连、一直连不上」，
 * 从服务端视角看是一条被伪装成负载的无效请求流。</p>
 *
 * <p>关闭顺序是「先关连接、再抹在线记录」。反过来的话，断开事件可能在抹除之后才到达，
 * 那时 {@code offline()} 会对着一个空键做 SCARD 再删除，虽然无害，但日志里会出现
 * 「下线一个从未上线的连接」这种干扰项。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAccountKicker implements AccountKicker {

    /** 1008 Policy Violation + 原因标记，见 {@code CloseStatus} 规范：reason 不超过 123 字节。 */
    private static final CloseStatus KICKED = CloseStatus.POLICY_VIOLATION.withReason("ACCOUNT_DISABLED");

    private final PresenceService presenceService;
    private final WebSocketSessionRegistry registry;

    @Override
    public int kick(Long userId) {
        if (userId == null) {
            return 0;
        }
        Set<String> sessionIds = presenceService.sessionIds(userId);
        int closed = 0;
        for (String sessionId : sessionIds) {
            if (registry.close(sessionId, KICKED)) {
                closed++;
            }
        }
        presenceService.offlineAll(userId);
        if (closed > 0) {
            log.info("账号 {} 被禁用，已断开 {} 条在线连接（记录 {} 条）", userId, closed, sessionIds.size());
        } else if (!sessionIds.isEmpty()) {
            // 记录有、句柄没有：说明这些连接在本实例启动前就存在，或已经自然断开。
            // 不是错误，但值得留一条线索——排查「禁用了但人还在线」时会用到。
            log.debug("账号 {} 的 {} 条在线记录没有对应的活会话（可能已自然断开）", userId, sessionIds.size());
        }
        return closed;
    }
}
