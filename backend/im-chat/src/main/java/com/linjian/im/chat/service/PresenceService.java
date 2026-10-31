package com.linjian.im.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * 这个人当前的所有连接标识。用于「禁用时立刻断开」——只有拿到 sessionId
     * 才能找到对应的 WebSocket 会话。
     *
     * <p>返回的是 Redis 里记录的快照，可能包含刚刚断开的连接：断开事件与
     * 管理操作并发时顺序不保证。调用方必须容忍对不存在的会话执行关闭（那是无操作）。</p>
     */
    public Set<String> sessionIds(Long userId) {
        Set<String> members = redis.opsForSet().members(key(userId));
        return members == null ? Set.of() : members;
    }

    /**
     * 直接抹掉这个人的全部在线记录。
     *
     * <p>正常断开时由 {@code offline(userId, sessionId)} 逐条移除，不需要这个。它用在
     * 「因为禁用而被踢下线」这个场景：那边是一次性关闭所有连接，
     * 与其依赖每条连接各自触发一次断开事件（事件是异步的、可能丢），
     * 不如直接删键，让「被禁用的人不在在线列表里」成为一个确定的事实。</p>
     */
    public void offlineAll(Long userId) {
        redis.delete(key(userId));
    }

    /**
     * 批量判断在线，一次 pipeline 拿全部结果。
     *
     * <p>群成员列表要显示每个人的在线状态。逐个 {@link #isOnline} 调用在 200 人群
     * 就是 200 次往返——这正是 W8 决定「群消息不做在线判断」的同一个理由，
     * 只是成员列表无法回避「每个人都要一个状态」，于是改成一次 pipeline。</p>
     *
     * <p>用 {@code EXISTS} 而不是 {@code SCARD}：Redis 会在 Set 的最后一个成员被移除时
     * 自动删掉键，因此「键存在」与「至少一个连接在线」等价，而 EXISTS 只需查键的元信息。</p>
     */
    public Set<Long> filterOnline(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = new ArrayList<>(userIds);
        List<Object> results = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (Long id : ids) {
                connection.keyCommands().exists(key(id).getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });

        Set<Long> online = new HashSet<>();
        for (int i = 0; i < ids.size() && i < results.size(); i++) {
            if (truthy(results.get(i))) {
                online.add(ids.get(i));
            }
        }
        return online;
    }

    /** pipeline 的结果类型随命令而定（EXISTS 可能回 Boolean，也可能回 Long），两种都认。 */
    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.longValue() > 0;
        }
        return false;
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

    /**
     * 列出当前所有在线用户，供管理端监控页使用。
     *
     * <p>这是本项目里唯一一处「不知道具体键名、只能扫全库」的读操作。用 SCAN 而不是
     * KEYS：后者会一次性阻塞整个 Redis，在键数增长时会拖垮所有正在收消息的连接，
     * 而这只是管理端一个展示页面。</p>
     *
     * <p>返回的是「有在线键的用户」，与 {@link #isOnline} 口径一致（键存在即在线）。
     * 单实例部署下这就是准确值；多实例部署时仍成立，因为键本身不带实例标识。</p>
     */
    public List<Long> listOnlineUserIds() {
        List<Long> ids = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(500).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                String suffix = cursor.next().substring(KEY_PREFIX.length());
                try {
                    ids.add(Long.parseLong(suffix));
                } catch (NumberFormatException ignored) {
                    // 键名不是「前缀 + 数字」时跳过。人工在 Redis 里留下的测试键
                    // 不该让整个在线列表报错。
                }
            }
        }
        return ids;
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
