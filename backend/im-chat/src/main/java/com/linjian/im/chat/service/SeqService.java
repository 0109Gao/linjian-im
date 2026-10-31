package com.linjian.im.chat.service;

import com.linjian.im.common.api.ErrorCode;
import com.linjian.im.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 会话内序号分配器。
 *
 * <p>用 Redis {@code INCR} 而不是数据库自增列或 {@code MAX(seq)+1}：后者在并发下必须
 * 加锁或靠唯一索引重试，而 INCR 是单线程原子的，一次往返就拿到号。</p>
 *
 * <p><b>代价必须写明：seq 允许出现空洞。</b>INCR 必须先于 INSERT 执行（INSERT 要写 seq），
 * 所以「拿到号之后插入失败」或「拿到号之后进程被杀」都会消耗掉一个号，
 * 库里留下 1,2,4,5 这样的序列。所有读取路径都用 {@code seq > N} 的范围查询，
 * 不假设连续，因此空洞不影响正确性；未读数走 {@code COUNT(im_inbox)}，
 * 也不依赖「最大 seq 减已读 seq」这个会被空洞污染的公式。</p>
 *
 * <p>另一个边界：Redis 里的计数如果丢了（比如 flushall），重启后会从 1 重新开始，
 * 与库里已有的 seq 冲突。唯一索引 {@code uk_session_seq} 会把这种冲突变成插入失败，
 * 而不是静默产生重复序号。W7 不实现「从库里重建计数」的自动修复，因为它需要一个
 * 冷启动扫描，而当前场景（单机、无持久化风险以外）不值得。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeqService {

    private static final String KEY_PREFIX = "im:seq:";

    private final StringRedisTemplate redis;

    public long next(Long sessionId) {
        Long value = redis.opsForValue().increment(KEY_PREFIX + sessionId);
        if (value == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "会话序号分配失败：Redis 未返回计数");
        }
        return value;
    }

    /** 仅供测试与排障：读当前计数，不推进。 */
    public long current(Long sessionId) {
        String v = redis.opsForValue().get(KEY_PREFIX + sessionId);
        return v == null ? 0L : Long.parseLong(v);
    }

    public void reset(Long sessionId) {
        redis.delete(KEY_PREFIX + sessionId);
        log.debug("已清除会话序号计数 sessionId={}", sessionId);
    }
}
