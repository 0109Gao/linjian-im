package com.linjian.im.common.api;

import lombok.Getter;

/**
 * 全局错误码。编号段说明：
 * <ul>
 *   <li>0            成功</li>
 *   <li>4000 / 404   通用请求错误</li>
 *   <li>4010         未登录或凭证无效</li>
 *   <li>4030         已认证但无权限（非管理员访问管理端接口）</li>
 *   <li>4101-4104    会话与消息领域错误（技术方案第 11 章）</li>
 *   <li>4110-4113    账号领域错误</li>
 *   <li>4120-4121    管理端领域错误（W9）</li>
 *   <li>5000         服务内部错误</li>
 * </ul>
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "成功"),

    PARAM_INVALID(4000, "参数不合法"),
    NOT_FOUND(404, "资源不存在"),

    UNAUTHORIZED(4010, "未登录或凭证已失效"),
    FORBIDDEN(4030, "无权限访问该资源"),

    NOT_IN_SESSION(4101, "不在该会话中"),
    INVALID_CONTENT(4102, "消息内容非法"),
    SESSION_NOT_FOUND(4103, "会话不存在"),
    DUPLICATE_MESSAGE(4104, "重复消息"),

    USER_NOT_FOUND(4110, "用户名不存在"),
    BAD_CREDENTIALS(4111, "用户名或密码错误"),
    USERNAME_TAKEN(4112, "用户名已被占用"),
    ACCOUNT_DISABLED(4113, "账号已被禁用"),

    CANNOT_DISABLE_SELF(4120, "不能禁用自己的账号"),
    ADMIN_OPERATION_REJECTED(4121, "管理操作被拒绝"),

    INTERNAL_ERROR(5000, "服务内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
