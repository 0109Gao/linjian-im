package com.linjian.im.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 在线状态。
 *
 * <p>结构是 {@code im:online:{userId}} 一个 Set，成员是 sessionId。用 Set 而不是
 * 单个 String，是因为同一个用户可能多端在线——推送时要知道这个人有几条活连接。</p>
 *
 * <p>为什么靠「启动清空 + TTL 兜底」而不是精确续期：SimpleBroker 的心跳是帧级的，
 * Spring 不会为心跳暴露任何事件，能拿到的只有连接建立与断开两个事件。
 * 想做到「心跳续期」就得自己解析心跳帧，为一个 W7 不需要的精度付高代价。
 * 于是取三层：连接事件维护 Set、应用启动时清空全部在线键（清掉上次崩溃留下的残留）、
 * key 带 5 分钟 TTL 并由定时任务续期（兜住进程被 kill -9 的情况）。</p>
 *
 * <p><b>这个方案的前提是单机单实例。</b>多实例部署时「启动清空」会误删其他实例的在线记录，
 * 届时必须换成「按 instanceId 分键 + 各实例只清自己的」。这一条写进了文档的已知限制。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceService {

    private static final String KEY_PREFIX = "im:online:";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;

    public void online(Long userId, String sessionId) {
        String key = key(userId);
        redis.opsForSet().add(key, sessionId);
        redis.expire(key, TTL);
    }

    public void offline(Long userId, String sessionId) {
        String key = key(userId);
        redis.opsForSet().remove(key, sessionId);
        // 成员清空后顺手删键，避免留下空 Set 占位（Redis 里空 Set 会被自动删除，这里只是显式表达意图）
        Long size = redis.opsForSet().size(key);
        if (size != null && size == 0) {
            redis.delete(key);
        }
    }

    public boolean isOnline(Long userId) {
        Long size = redis.opsForSet().size(key(userId));
        return size != null && size > 0;
    }

    public long onlineSessionCount(Long userId) {
        Long size = redis.opsForSet().size(key(userId));
        return size == null ? 0L : size;
    }

    /**
     * 清空全部在线键。只在应用启动时调用一次。
     *
     * <p>用 SCAN 而不是 {@code KEYS *}：KEYS 在大库上会阻塞整个 Redis，
     * 这是一个连单机项目也不该养成的习惯。</p>
     */
    public int clearAll() {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(500).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        if (keys.isEmpty()) {
            return 0;
        }
        Long removed = redis.delete(keys);
        log.info("启动清理在线状态残留：删除 {} 个键", removed);
        return removed == null ? 0 : removed.intValue();
    }

    /** 定时续期：让键在进程存活期间不过期，进程死掉后靠 TTL 自动消失。 */
    public int renewAll() {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(500).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        keys.forEach(k -> redis.expire(k, TTL));
        return keys.size();
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
