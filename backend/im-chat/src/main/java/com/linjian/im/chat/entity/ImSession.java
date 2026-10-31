package com.linjian.im.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话。单聊与群聊共用一张表，用 {@code sessionType} 区分。
 *
 * <p>{@code bizKey} 只在单聊时非空，值为「较小 userId : 较大 userId」。
 * 把「谁和谁」编码成一个可加唯一索引的字符串，是为了让「两个人同时点开对方」
 * 这种并发重复创建由数据库裁决——应用层先查后插的写法在这个场景下必然漏。</p>
 *
 * <p>{@code lastMsg*} 三个字段是刻意的反范式：会话列表必须按「最近有消息」排序，
 * 如果靠子查询取最后一条，排序就无法走索引。代价是每条消息多一次 UPDATE，
 * 而这次 UPDATE 本来就要更新会话活跃时间，属于同一件事。</p>
 */
@Data
@TableName("im_session")
public class ImSession {

    public static final int TYPE_SINGLE = 1;
    public static final int TYPE_GROUP = 2;

    @TableId(type = IdType.INPUT)
    private Long id;

    private Integer sessionType;

    /** 群名。单聊为 null——单聊的展示名来自对方昵称，不属于会话自身的属性。 */
    private String name;

    private String bizKey;

    private Long lastMsgSeq;

    private LocalDateTime lastMsgAt;

    private String lastMsgPreview;

    private LocalDateTime createdAt;
}
