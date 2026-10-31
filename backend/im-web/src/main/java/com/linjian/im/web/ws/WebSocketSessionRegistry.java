package com.linjian.im.web.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活着的 WebSocket 会话登记表，只为「主动断开」而存在。
 *
 * <p><b>为什么不能只靠 Redis 里的在线集合。</b>{@code im:online:{userId}} 记的是
 * 「谁在线、有几条连接」，但里面只有 sessionId 字符串，没有任何可以关闭的对象。
 * 想真正把连接掐掉，必须持有 {@link WebSocketSession} 的引用——而 Spring 的
 * {@code SimpUserRegistry} 只给出会话元数据，不给出句柄。所以这里维护一份
 * sessionId → session 的映射，由 {@code WebSocketConfig} 注册的装饰器负责增删。</p>
 *
 * <p>用 {@code ConcurrentHashMap}：写入发生在 IO 线程，读取与移除发生在管理端的
 * HTTP 线程，两者天然并发。值为 {@code WebSocketSession}，Spring 在
 * {@code setSendTimeLimit} 生效时会把它包成 {@code ConcurrentWebSocketSessionDecorator}，
 * 其 {@code close()} 与 {@code sendMessage()} 之间是加锁的，因此这里直接调 close 是安全的。</p>
 *
 * <p>不做「启动时清理」：进程启动时这张表必然是空的，它没有持久化，
 * 也不会留下脏数据。这一点和在线状态集合不同。</p>
 */
@Slf4j
@Component
public class WebSocketSessionRegistry {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public void add(String sessionId, WebSocketSession session) {
        if (sessionId != null && session != null) {
            sessions.put(sessionId, session);
        }
    }

    public void remove(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    /**
     * 关闭一个会话。会话不存在（已经断了）视为成功——调用方关心的是「最终是断的」。
     *
     * <p>刻意 catch 所有 {@link IOException}：另一个线程正在写帧时 close 可能抛
     * {@code EOFException} 或 {@code IOException: closed}，这不是故障，
     * 而且它发生在「管理员点禁用」这个业务流程里，不该冒泡出去把一次成功的禁用变成失败。
     */
    public boolean close(String sessionId, CloseStatus status) {
        WebSocketSession session = sessions.remove(sessionId);
        if (session == null) {
            return false;
        }
        try {
            if (session.isOpen()) {
                session.close(status);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("关闭会话失败 sessionId={}：{}", sessionId, e.getMessage());
            return false;
        }
    }

    /** 当前登记的连接数。仅用于测试与排障。 */
    public int size() {
        return sessions.size();
    }
}
