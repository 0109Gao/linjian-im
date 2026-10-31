package com.linjian.im.chat.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 拉人入群。
 *
 * <p>已经是成员的人会被静默跳过而不是报错：邀请是一个「谁在群里就跳过谁」的幂等操作，
 * 让整批失败会让客户端不得不在本地先查一遍成员列表，而那正是最容易过期的一份数据。</p>
 */
public record AddMembersRequest(
        @NotEmpty(message = "userIds 不能为空") List<Long> userIds
) {
}
