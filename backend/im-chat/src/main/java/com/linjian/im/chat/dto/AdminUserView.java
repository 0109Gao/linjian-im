package com.linjian.im.chat.dto;

import com.linjian.im.chat.entity.ImUser;

import java.time.LocalDateTime;

/**
 * 管理端的用户行。
 *
 * <p>与用户端的 {@code UserBrief} 分开，不是重复定义：{@code UserBrief} 是「给别人看的
 * 公开资料」（id / 用户名 / 昵称），这里是「给管理员看的运营数据」（还包含状态、角色、
 * 注册时间）。共用一个 DTO 的后果是把禁用状态和角色也发给了普通用户——
 * 那些字段对客户端没有意义，但对攻击者是有用的信息。</p>
 *
 * <p>不返回 {@code passwordHash}。它虽然在库里，但没有任何界面需要它，
 * 一旦出现在响应里，就会出现在浏览器 devtools、日志、和任何一次截图里。</p>
 */
public record AdminUserView(
        Long id,
        String username,
        String nickname,
        Integer status,
        Integer role,
        LocalDateTime createdAt
) {

    public static AdminUserView of(ImUser u) {
        return new AdminUserView(u.getId(), u.getUsername(), u.getNickname(),
                u.getStatus(), u.getRole() == null ? 0 : u.getRole(), u.getCreatedAt());
    }
}
