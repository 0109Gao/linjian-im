package com.linjian.im.chat.dto;

import jakarta.validation.constraints.NotNull;

/** 打开单聊会话的入参。会话不存在时由服务端懒创建。 */
public record OpenSessionRequest(@NotNull Long peerUserId) {
}
