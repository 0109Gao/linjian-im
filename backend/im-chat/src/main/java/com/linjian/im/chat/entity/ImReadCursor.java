package com.linjian.im.chat.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 已读游标。W7 只有「用户」维度，没有 {@code deviceType}。
 *
 * <p>方案原文写的是「每端已读位点」，这里刻意偏离：W6 的 STOMP CONNECT 帧里
 * 没有任何可靠的设备标识来源，若让客户端自报一个 deviceType 存进库，
 * 得到的是一个不可信的维度——它既不能用来做多端同步的判断，还会让
 * 「未读到底算谁的」这个问题变复杂。W8 做多端同步时再扩字段。</p>
 *
 * <p>联合主键 (session_id, user_id)，写入用 {@code INSERT ... ON DUPLICATE KEY UPDATE}
 * 配合 {@code GREATEST}，防止乱序上报把游标往回拽。</p>
 */
@Data
@TableName("im_read_cursor")
public class ImReadCursor {

    private Long sessionId;

    private Long userId;

    private Long lastReadSeq;

    private LocalDateTime updatedAt;
}
