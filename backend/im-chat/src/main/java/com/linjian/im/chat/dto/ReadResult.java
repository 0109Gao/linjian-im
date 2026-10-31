package com.linjian.im.chat.dto;

/** 已读上报的结果。返回剩余的未读数，客户端可以直接用它更新红点，省一次查询。 */
public record ReadResult(Long sessionId, long lastReadSeq, long unreadCount) {
}
