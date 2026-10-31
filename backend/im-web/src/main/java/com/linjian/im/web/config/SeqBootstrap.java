package com.linjian.im.web.config;

import com.linjian.im.chat.service.SeqService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 启动时按库里的真实最大 seq 校准 Redis 计数。
 *
 * <p><b>为什么必须有这一步。</b>seq 计数是派生数据，真值在 {@code im_message}。
 * Redis 被清空（{@code FLUSHALL}、换实例没接上 AOF、运维手抖删键）之后，
 * {@code INCR} 会从 1 重新开始，与库里已有的 seq 撞 {@code uk_session_seq}。
 * 表现是最难查的那一类：<b>服务健康、登录正常、断线重连正常，只有「发消息」永远失败</b>，
 * 而且日志里是一条主键冲突，看不出根因在 Redis。</p>
 *
 * <p>启动时扫一遍 {@code MAX(seq)} 把计数推回去，是唯一能在「不需要人工介入」
 * 的前提下消除这个状态的做法。代价是启动多一次全表分组查询（有覆盖索引，
 * 万级会话在百毫秒内），只在进程启动时发生。</p>
 *
 * <p><b>为什么用 {@code @PostConstruct} 而不是像 {@code PresenceBootstrap} 那样监听
 * {@code ApplicationReadyEvent}。</b>两者的时机要求不同：在线状态清理晚一点没关系，
 * 顶多是头几百毫秒里有人被误显示为在线；而计数校准必须早于「第一条消息进来」——
 * 一旦在窗口期内 INCR 出一个小号并撞上唯一键，那条消息就永久失败了（客户端会看到发送失败，
 * 而不是被服务端修正）。{@code @PostConstruct} 在容器刷新阶段执行，
 * 早于内嵌 Tomcat 开始接受连接，因此这个窗口不存在。</p>
 *
 * <p>失败不阻断启动：Redis 或库不可用时打一条 WARN 继续。此时发消息本来就会失败
 * （INCR 直接抛异常），把整个应用卡在启动阶段只会让故障表现得更难判断。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeqBootstrap {

    private final SeqService seqService;

    @PostConstruct
    public void rebuildSeqCounters() {
        try {
            SeqService.RebuildResult result = seqService.rebuildAll();
            if (result.raised() > 0) {
                log.warn("序号计数与库内最大 seq 不一致，已修正 {} 个会话（共推高 {} 个号）"
                                + "——通常意味着 Redis 里的 im:seq:* 丢失过，请确认持久化配置",
                        result.raised(), result.delta());
            } else {
                log.info("序号计数校验通过：{} 个会话的计数均不低于库内最大 seq", result.sessions());
            }
        } catch (RuntimeException e) {
            log.warn("序号计数校准失败，服务继续启动（此时发消息会失败，恢复后重启或用管理端接口重建）：{}",
                    e.getMessage());
        }
    }
}
