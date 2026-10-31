package com.linjian.im.chat.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 修改账号状态。
 *
 * <p>用 {@link Integer} 而不是 {@code int}：若用基本类型，请求体里漏传字段时
 * Jackson 会给它填 0，而 0 恰好是「禁用」——一个漏传的字段会静默地把账号停掉。
 * 用包装类型则得到 null，能被 {@code @NotNull} 拦成一个明确的 400。</p>
 */
public record UpdateStatusRequest(@NotNull(message = "status 不能为空") Integer status) {
}
