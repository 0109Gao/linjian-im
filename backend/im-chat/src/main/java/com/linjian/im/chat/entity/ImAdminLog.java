package com.linjian.im.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员操作日志。一行 = 一次管理动作。
 *
 * <p>主键策略与其它实体一致用 {@code INPUT}，ID 由 {@code SnowflakeIdGenerator} 生成后写入。</p>
 *
 * <p>{@code adminName} 是冗余字段，不通过 {@code admin_id} 关联 {@code im_user} 查出来。
 * 审计记录必须不可变——一旦允许关联，账号改名就等于「重写历史」，
 * 账号被删则整行失去主体，恰好是查旧账时最需要它的时刻。</p>
 */
@Data
@TableName("im_admin_log")
public class ImAdminLog {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long adminId;

    private String adminName;

    /** 见 {@code AdminActions}。用字符串而不是枚举落库：加动作不需要改表结构。 */
    private String action;

    /** USER / SESSION / SYSTEM */
    private String targetType;

    /** SYSTEM 类操作没有目标，为 null。 */
    private Long targetId;

    /** 人可读的补充说明，例如「账号 demo 状态：正常 → 禁用」。 */
    private String detail;

    private String ip;

    private LocalDateTime createdAt;
}
