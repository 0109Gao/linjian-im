package com.linjian.im.common.exception;

import com.linjian.im.common.api.ErrorCode;
import lombok.Getter;

/**
 * 可预期的业务异常。抛出该异常表示「请求本身合法，但业务规则不允许」，
 * 由 {@link GlobalExceptionHandler} 转成对应错误码，不打印堆栈。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
