package com.linjian.im.common.api;

import org.slf4j.MDC;

/**
 * 从 MDC 读取当前请求的 traceId。非 Web 线程（例如测试线程、定时任务线程）读取到 null，
 * 此时响应体 traceId 为空，属预期行为，不抛异常。
 */
public final class TraceId {

    public static final String MDC_KEY = "traceId";

    private TraceId() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
