package com.linjian.im.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息本体。全局一份，读扩散——群聊也只存一条，靠成员关系过滤，而不是每人一份副本。
 *
 * <p>{@code seq} 是会话内序号，由 Redis {@code INCR} 分配，<b>允许出现空洞</b>：
 * INCR 成功但随后 INSERT 失败时，那个号就被消耗掉了。所有读取路径都用
 * {@code seq > N} 的范围查询，不假设连续，因此空洞不影响正确性。</p>
 *
 * <p>{@code clientMsgId} 由客户端生成并在重发时保持不变，(sender_id, client_msg_id)
 * 上的唯一索引是幂等的最后一道闸。</p>
 */
@Data
@TableName("im_message")
public class ImMessage {

    public static final int CONTENT_TYPE_TEXT = 1;

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long sessionId;

    private Long seq;

    private Long senderId;

    private Integer contentType;

    private String content;

    private String clientMsgId;

    private LocalDateTime createdAt;
}
