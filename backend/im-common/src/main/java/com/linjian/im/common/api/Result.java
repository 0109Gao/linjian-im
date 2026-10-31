package com.linjian.im.common.api;

import lombok.Data;

/**
 * 统一响应体。所有 REST 接口一律返回该结构，STOMP 侧不使用（STOMP 以帧为载体的消息自带语义）。
 *
 * <p>traceId 由 {@code TraceIdFilter} 写入 MDC 后在响应体中原样带出，便于把一次前端报错
 * 与服务端日志对上。</p>
 */
@Data
public class Result<T> {

    private int code;
    private String message;
    private T data;
    private String traceId;

    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.setCode(ErrorCode.SUCCESS.getCode());
        r.setMessage(ErrorCode.SUCCESS.getMessage());
        r.setData(data);
        r.setTraceId(TraceId.current());
        return r;
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> error(ErrorCode errorCode) {
        return error(errorCode, errorCode.getMessage());
    }

    public static <T> Result<T> error(ErrorCode errorCode, String message) {
        Result<T> r = new Result<>();
        r.setCode(errorCode.getCode());
        r.setMessage(message);
        r.setTraceId(TraceId.current());
        return r;
    }
}
