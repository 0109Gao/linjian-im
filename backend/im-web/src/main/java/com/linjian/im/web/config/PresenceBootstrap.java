package com.linjian.im.web.config;

import com.linjian.im.chat.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时清空在线状态。
 *
 * <p>进程被 {@code kill -9} 时不会触发断开事件，Redis 里会留下「在线」但实际已断的条目，
 * 表现为会话列表里对方一直显示在线。启动时清空是最省事的修正方式。</p>
 *
 * <p><b>这个做法只在单实例下成立。</b>多实例部署时，任何一个实例启动都会清掉其他实例
 * 的在线记录，届时必须改成「按 instanceId 分键，各实例只清自己的」。
 * 这一条写进了文档的已知限制，不是遗漏。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PresenceBootstrap {

    private final PresenceService presenceService;

    @EventListener(ApplicationReadyEvent.class)
    public void clearStalePresence() {
        try {
            int removed = presenceService.clearAll();
            log.info("在线状态初始化完成，清理残留键 {} 个", removed);
        } catch (RuntimeException e) {
            // Redis 不可用时不应该阻止应用启动：鉴权、落库、拉取都不依赖在线状态，
            // 只是「对方是否在线」这个展示字段暂时不准。
            log.warn("在线状态初始化失败，服务继续启动：{}", e.getMessage());
        }
    }
}
