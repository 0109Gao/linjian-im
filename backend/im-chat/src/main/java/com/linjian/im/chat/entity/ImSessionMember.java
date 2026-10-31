package com.linjian.im.chat.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话成员。主键是 (session_id, user_id) 联合主键，因此本实体没有单列 {@code id}，
 * 对应的 Mapper 也就不继承 {@code BaseMapper}——MyBatis-Plus 的通用 CRUD 依赖单主键，
 * 强行套用只会得到一个空的主键条件。
 *
 * <p>这张表在 W7 承担两个职责：会话列表的入口查询（按 user_id 找会话），
 * 以及发消息前的成员鉴权。两者共用 {@code idx_user} 索引。</p>
 */
@Data
@TableName("im_session_member")
public class ImSessionMember {

    private Long sessionId;

    private Long userId;

    private LocalDateTime joinedAt;
}
