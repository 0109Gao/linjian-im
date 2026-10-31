package com.linjian.im.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账号实体。ID 由 {@code SnowflakeIdGenerator} 生成后显式写入，
 * 因此主键策略设为 INPUT 而不是 ASSIGN_ID——否则 MyBatis-Plus 会用自己的雪花算法覆盖掉。
 *
 * <p>只存密码哈希，不存明文，也不存可逆密文。BCrypt 自带盐，因此没有单独的 salt 字段。</p>
 */
@Data
@TableName("im_user")
public class ImUser {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String username;

    private String passwordHash;

    private String nickname;

    /** 1 正常 / 0 禁用。禁用状态不允许登录，且已发出的 token 会在下次请求时被拒。 */
    private Integer status;

    /** 0 普通用户 / 1 管理员。管理员才能访问 /api/admin/**。 */
    private Integer role;

    private LocalDateTime createdAt;
}
