package com.linjian.im.chat.dto;

/** 按用户名或昵称搜索出来的用户，用于发起会话。只暴露必要字段，不返回 password_hash。 */
public record UserBrief(Long id, String username, String nickname) {
}
