package com.linjian.im.chat.service;

import com.linjian.im.chat.mapper.ImMessageMapper;
import com.linjian.im.common.api.ErrorCode;
import com.linjian.im.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

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
 * <p><b>计数丢失怎么办。</b>Redis 里的计数是派生数据——真值在
 * {@code im_message} 里（{@code MAX(seq)} per session）。计数一旦被清空
 * （{@code FLUSHALL}、换了实例没接上 AOF、运维清键），{@code INCR} 会从 1 重新开始，
 * 与库里已有的 seq 撞上 {@code uk_session_seq}，表现为<b>每一条消息都插入失败</b>，
 * 而服务本身看起来完全正常。修复手段是 {@link #rebuildAll()}：
 * 按库里的 {@code MAX(seq)} 把计数推到不低于真实值。启动时由
 * {@code SeqBootstrap} 自动执行一次，运行期也可以由管理端手动触发。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeqService {

    private static final String KEY_PREFIX = "im:seq:";

    /**
     * 只升不降地把计数推到目标值，返回「推高了多少」。
     *
     * <p>必须用脚本而不是「先 GET 再 SET」：两次往返之间可能有别的请求已经 INCR 过，
     * 直接 SET 会把别人刚分配的号覆盖回去，制造出重复 seq。
     * 只要判断和写入在同一个脚本里，Redis 的单线程执行模型就保证了它不会与其他命令交错。</p>
     *
     * <p><b>不做「降级」是有意的</b>：库里 MAX(seq) 小于计数是正常状态
     * （分配过但插入失败的那些号就是空洞），把计数压回去会让下一次 INCR 重新发一个
     * 已经用过的号，正好撞唯一键。所以这里只取两者较大值。</p>
     */
    private static final RedisScript<Long> RAISE_TO = new DefaultRedisScript<>("""
            local cur = tonumber(redis.call('GET', KEYS[1]) or '0')
            local want = tonumber(ARGV[1])
            if want > cur then
                redis.call('SET', KEYS[1], want)
                return want - cur
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ImMessageMapper messageMapper;

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

    /**
     * 按库里的真实最大值重建全部会话的计数。
     *
     * <p>幂等：已经正确的计数一轮扫下来零改动，可以在任何时候重复执行。
     * 不会降低任何计数，因此对「正在服务中的实例」也是安全的——
     * 最坏情况是它读到的 MAX 比此刻的计数小，脚本判断后什么都不做。</p>
     *
     * @return 扫描到的会话数、被推高的会话数、总共推高了多少号
     */
    public RebuildResult rebuildAll() {
        List<ImMessageMapper.SessionSeqRow> rows = messageMapper.maxSeqBySession();
        int raised = 0;
        long total = 0;
        for (ImMessageMapper.SessionSeqRow row : rows) {
            if (row.getSessionId() == null || row.getMaxSeq() == null) {
                continue;
            }
            Long delta = redis.execute(RAISE_TO,
                    List.of(KEY_PREFIX + row.getSessionId()),
                    String.valueOf(row.getMaxSeq()));
            if (delta != null && delta > 0) {
                raised++;
                total += delta;
                log.info("会话 {} 的序号计数被推高 {}（库内最大 seq = {}）",
                        row.getSessionId(), delta, row.getMaxSeq());
            }
        }
        log.info("序号计数重建完成：扫描 {} 个有消息的会话，修正 {} 个，共推高 {} 个号",
                rows.size(), raised, total);
        return new RebuildResult(rows.size(), raised, total);
    }

    /**
     * @param sessions 库里有消息的会话数
     * @param raised   计数被修正的会话数（正常情况下为 0）
     * @param delta    合计推高的号数，即「原本会撞唯一键的消息条数」下界
     */
    public record RebuildResult(int sessions, int raised, long delta) {
    }
}
