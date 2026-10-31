package com.linjian.im.common.id;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 雪花 ID 生成器。
 *
 * <p>位布局（64 位，最高位恒为 0 以保证正数）：</p>
 * <pre>
 * | 1 bit 符号 | 41 bit 毫秒时间戳 | 10 bit 机器号 | 12 bit 序列号 |
 * </pre>
 *
 * <p>单机每秒理论上限 4096 × 1000 = 409.6 万，远高于本项目的实际需求。
 * 时钟回拨采用「等待追平」而不是「抛异常」：回拨量通常只有几毫秒，等待代价小于让请求失败；
 * 但回拨超过 5 秒说明系统时钟被人为改动，此时抛异常，避免生成重复 ID。</p>
 */
@Component
public class SnowflakeIdGenerator {

    /** 起始纪元：2026-01-01T00:00:00Z。选在项目开始之后，可把 41 位时间戳用满 69 年。 */
    private static final long EPOCH = 1767225600000L;

    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    /** 允许的最大时钟回拨容忍量，单位毫秒。 */
    private static final long MAX_BACKWARD_MS = 5000L;

    @Value("${im.snowflake.worker-id:1}")
    private long workerId;

    private long sequence = 0L;
    private long lastTimestamp = -1L;

    @PostConstruct
    void validate() {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalStateException(
                    "im.snowflake.worker-id 必须在 0~" + MAX_WORKER_ID + " 之间，当前为 " + workerId);
        }
    }

    public synchronized long nextId() {
        long now = System.currentTimeMillis();

        if (now < lastTimestamp) {
            long backward = lastTimestamp - now;
            if (backward > MAX_BACKWARD_MS) {
                throw new IllegalStateException(
                        "检测到系统时钟回拨 " + backward + "ms，超过容忍上限 " + MAX_BACKWARD_MS + "ms，拒绝生成 ID");
            }
            now = waitUntil(lastTimestamp);
        }

        if (now == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                now = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = now;
        return ((now - EPOCH) << TIMESTAMP_SHIFT) | (workerId << WORKER_ID_SHIFT) | sequence;
    }

    private long waitUntil(long target) {
        long now = System.currentTimeMillis();
        while (now < target) {
            Thread.onSpinWait();
            now = System.currentTimeMillis();
        }
        return now;
    }
}
