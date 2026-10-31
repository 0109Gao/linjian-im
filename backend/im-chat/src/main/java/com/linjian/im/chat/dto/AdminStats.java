package com.linjian.im.chat.dto;

/**
 * 管理端概览指标。
 *
 * <p>五个数字各自回答一个不同的问题，不是随便凑的：</p>
 * <ul>
 *   <li>{@code userCount} / {@code groupCount}：系统有多大</li>
 *   <li>{@code messageCount} / {@code todayMessageCount}：存量与今天的增量——
 *       只看总量看不出「系统是活的还是死的」</li>
 *   <li>{@code onlineCount}：此刻有多少条在线记录（按用户算，不是连接数）</li>
 * </ul>
 *
 * <p>{@code messageCount} 是全表 COUNT，随数据量线性变慢，这是已知代价；
 * 见 {@code ImMessageMapper#countAll} 的说明。</p>
 */
public record AdminStats(
        long userCount,
        long groupCount,
        long sessionCount,
        long messageCount,
        long todayMessageCount,
        long onlineCount
) {
}
