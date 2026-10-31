package com.linjian.im.common.exception;

import com.linjian.im.common.api.ErrorCode;
import com.linjian.im.common.api.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常出口。三类分支刻意分开，避免把「用户输错密码」和「代码抛 NPE」记成同一种日志：
 * <ol>
 *   <li>业务异常 → 按 ErrorCode 返回，WARN 级别，不带堆栈</li>
 *   <li>参数校验失败 → 4000，并把字段错误拼进 message</li>
 *   <li>未知异常 → 5000，ERROR 级别，带堆栈（这类才需要人看）</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常 code={} message={}", e.getErrorCode().getCode(), e.getMessage());
        return Result.error(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败: {}", detail);
        return Result.error(ErrorCode.PARAM_INVALID, detail);
    }

    /**
     * Spring 6 抛的是 NoResourceFoundException（NoHandlerFoundException 已废弃）。
     * 这里显式降级为 404，否则会落到下面的 Exception 兜底，把「路径敲错」记成「服务出错」。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResource(NoResourceFoundException e, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        log.debug("路径不存在: {}", e.getResourcePath());
        return Result.error(ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnknown(Exception e) {
        log.error("未捕获异常", e);
        return Result.error(ErrorCode.INTERNAL_ERROR);
    }
}
