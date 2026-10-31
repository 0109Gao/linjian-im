package com.linjian.im.chat.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 建群入参。
 *
 * <p>群名长度不在 Bean Validation 里用 {@code @Size} 限制：{@code @Size} 数的是
 * UTF-16 单元，一个 emoji 算 2，而服务端实际按 Unicode 码点校验（与消息内容一致）。
 * 两套口径并存会让「同一个群名，一处通过一处被拒」，因此这里只挡空值，
 * 长度统一交给 {@code SessionService} 按码点判断。</p>
 *
 * <p>{@code memberIds} 允许为 null（等同空列表），但不允许建成「只有自己」的群——
 * 一个只有自己的群没有任何使用场景，只会在会话列表里占位。</p>
 */
public record CreateGroupRequest(
        @NotBlank(message = "群名不能为空") String name,
        List<Long> memberIds
) {
}
