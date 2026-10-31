package com.linjian.im.chat.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 上报已读位点。
 *
 * <p>带 {@code lastReadSeq} 而不是「已读全部」：客户端可能只滚到了第 20 条，
 * 后面还有 5 条没看，服务端不能替用户把这 5 条也标成已读。</p>
 */
public record ReadRequest(@NotNull Long lastReadSeq) {
}
