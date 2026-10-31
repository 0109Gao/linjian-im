package com.linjian.im.chat.dto;

import java.util.List;

/**
 * 消息拉取结果。
 *
 * <p>有 {@code hasMore} 而没有 total：消息是无限增长的，每次为前端算一个精确总数
 * 都要一次全表 count，而前端真正需要的只有「还能不能继续往上翻」这一个布尔值。
 * 实现方式是每次都多取一条，取到了就说明还有。</p>
 */
public record MessagePage(List<MessageView> items, boolean hasMore) {
}
