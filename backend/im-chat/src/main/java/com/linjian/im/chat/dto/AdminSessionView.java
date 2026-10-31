package com.linjian.im.chat.dto;

import java.time.LocalDateTime;

/**
 * 管理端的会话行。
 *
 * <p>刻意不返回成员 ID 列表：一个 200 人群会把响应撑到几十 KB，而管理员在列表页
 * 只需要知道「多大」，具体是谁在详情里查。</p>
 *
 * <p>{@code lastMsgSeq} 是会话内最后一跳的序号，不是消息条数。两者不相等——
 * seq 由 {@code INCR} 分配，失败或回滚的发送会留下空洞（这是 W7 明确接受的取舍）。
 * 字段名用 {@code lastMsgSeq} 而不是 {@code messageCount}，就是为了不让使用者
 * 把这两个数字混为一谈。</p>
 */
public record AdminSessionView(
        Long id,
        Integer sessionType,
        String name,
        String bizKey,
        int memberCount,
        Long lastMsgSeq,
        LocalDateTime lastMsgAt,
        String lastMsgPreview,
        LocalDateTime createdAt
) {
}
