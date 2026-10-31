package com.linjian.im.chat.dto;

import com.linjian.im.chat.entity.ImAdminLog;

import java.time.LocalDateTime;

/**
 * 操作日志行。
 *
 * <p>{@code actionText} 由服务端给出中文，而不是让前端维护一张映射表：
 * 日志是给人看的，新增动作时前端不该跟着发一次版才能显示正确文案；
 * 同时把原始 {@code action} 一起返回，前端要做筛选或统计时用得上。</p>
 */
public record AdminLogView(
        Long id,
        Long adminId,
        String adminName,
        String action,
        String actionText,
        String targetType,
        Long targetId,
        String detail,
        String ip,
        LocalDateTime createdAt
) {

    public static AdminLogView of(ImAdminLog row) {
        return new AdminLogView(
                row.getId(),
                row.getAdminId(),
                row.getAdminName(),
                row.getAction(),
                text(row.getAction()),
                row.getTargetType(),
                row.getTargetId(),
                row.getDetail(),
                row.getIp(),
                row.getCreatedAt());
    }

    private static String text(String action) {
        if (action == null) {
            return "";
        }
        return switch (action) {
            case "USER_DISABLE" -> "禁用账号";
            case "USER_ENABLE" -> "恢复账号";
            case "MSG_AUDIT_VIEW" -> "查看消息";
            case "SEQ_REBUILD" -> "重建序号";
            default -> action;
        };
    }
}
