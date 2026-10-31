package com.linjian.im.chat.job;

import com.linjian.im.chat.mapper.ImInboxMapper;
import com.linjian.im.chat.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 两个后台任务。它们都不参与主链路，因此即使全部失败也只影响「历史残留」与「在线状态顺延」，
 * 不影响收发消息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaintenanceJob {

    /** 超过这个天数仍未读的收件箱记录视为残留。 */
    private static final int INBOX_RETENTION_DAYS = 30;

    /** 每批删除的行数。一次删太多会长时间持锁并撑大 undo log。 */
    private static final int DELETE_BATCH = 5000;

    /** 单轮最多删几批，避免某个异常数据把任务拖成死循环。 */
    private static final int MAX_BATCHES = 20;

    private final ImInboxMapper inboxMapper;
    private final PresenceService presenceService;

    /**
     * 收件箱兜底清理。
     *
     * <p>正常情况下未读行会在用户上报已读时被删掉，这里清的是「一直没读」的残留——
     * 账号长期不上线、或者客户端从未上报过已读的会话。没有这个任务，这些行会永久累积。</p>
     */
    @Scheduled(cron = "0 0 4 * * ?")
    public void cleanupInbox() {
        LocalDateTime deadline = LocalDateTime.now().minusDays(INBOX_RETENTION_DAYS);
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int deleted = inboxMapper.deleteBefore(deadline, DELETE_BATCH);
            total += deleted;
            if (deleted < DELETE_BATCH) {
                break;
            }
        }
        if (total > 0) {
            log.info("收件箱兜底清理完成：删除 {} 行（早于 {}）", total, deadline);
        }
    }

    /**
     * 在线状态续期。
     *
     * <p>SimpleBroker 不为心跳暴露事件，拿不到「这条连接还活着」的信号，
     * 因此只能由进程自己定期给在线键续 TTL。进程挂掉后续期停止，
     * 键在 5 分钟内自然消失，不会留下永久的假在线。</p>
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void renewPresence() {
        int count = presenceService.renewAll();
        if (count > 0) {
            log.debug("在线状态续期：{} 个用户键", count);
        }
    }
}
