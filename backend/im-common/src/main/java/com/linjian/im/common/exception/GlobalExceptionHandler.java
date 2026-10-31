package com.linjian.im.common.exception;

import com.linjian.im.common.api.ErrorCode;
import com.linjian.im.common.api.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常出口。刻意分成四类分支，避免把四种性质不同的事记成同一种日志：
 * <ol>
 *   <li>业务异常 → 按 ErrorCode 返回，WARN 级别，不带堆栈</li>
 *   <li>参数校验失败 → 4000，并把字段错误拼进 message</li>
 *   <li>请求本身构造错误（类型不匹配 / 缺参数 / body 不是 JSON / 方法不对）
 *       → 4000 或 405，WARN 级别，不带堆栈</li>
 *   <li>未知异常 → 5000，ERROR 级别，带堆栈（这类才需要人看）</li>
 * </ol>
 *
 * <p>第 3 类必须单独处理。它们全都继承 RuntimeException，如果只留 {@code Exception} 兜底，
 * 结果是：任何人对任意端点发一次错请求（{@code GET} 打 {@code POST} 接口、
 * {@code ?page=abc}、{@code body} 写成 {@code {bad json}}），服务端都会记一条
 * ERROR 加完整堆栈。**这类日志不是故障信号，但它长得和故障一模一样**——
 * 真正出事时，报警会被这些噪声淹没。判断依据：错在请求方，就不该记成服务端的错。
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
     * 路径变量或 query 参数的类型不对，例如 {@code /api/sessions/abc/messages}、{@code ?page=abc}。
     * 这条以前落到 {@code Exception} 兜底，返回「服务内部错误」——用户看到这六个字
     * 只会以为服务挂了，然后来问运维。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String detail = "参数 " + e.getName() + " 格式不正确";
        log.warn("参数类型不匹配: name={} value={}", e.getName(), e.getValue());
        return Result.error(ErrorCode.PARAM_INVALID, detail);
    }

    /** 缺少必填 query 参数，例如 {@code /api/users/search} 没带 {@code q}。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        String detail = "缺少必填参数 " + e.getParameterName();
        log.warn("缺少必填参数: {}", e.getParameterName());
        return Result.error(ErrorCode.PARAM_INVALID, detail);
    }

    /** 请求体不是合法 JSON，或字段类型与 DTO 不兼容。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体无法解析: {}", e.getMostSpecificCause().getMessage());
        return Result.error(ErrorCode.PARAM_INVALID, "请求体格式不正确");
    }

    /**
     * HTTP 方法不对，例如用 {@code GET} 打 {@code POST /api/auth/login}。
     * 这条用真实的 405，而不是 200 + 业务码：它不属于「业务规则拒绝」，
     * 而是协议层就没对上，与 404 的处理方式保持一致。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {}", e.getMessage());
        return Result.error(ErrorCode.PARAM_INVALID, "请求方法不支持");
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
