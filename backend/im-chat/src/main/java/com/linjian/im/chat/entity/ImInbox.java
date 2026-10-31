package com.linjian.im.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 离线未读收件箱。语义是「该用户当前未读的消息引用集合」，<b>不是投递队列</b>。
 *
 * <p>决定这个语义的是未读数怎么算：一旦收件箱只保留未读行，未读数就是
 * {@code COUNT(*)}，一条走唯一索引前缀的查询，既不依赖 {@code maxSeq - lastReadSeq}
 * 这个公式，也就不会被 seq 空洞影响（空洞会让那个公式多算未读）。</p>
 *
 * <p>在线用户不写本表——推送到达即视为已读。只有投递时不在线的成员才写一行。</p>
 */
@Data
@TableName("im_inbox")
public class ImInbox {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long userId;

    private Long sessionId;

    private Long seq;

    private LocalDateTime createdAt;
}
