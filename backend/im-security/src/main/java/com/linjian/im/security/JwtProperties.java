package com.linjian.im.security;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * JWT 配置，并在容器启动阶段做强制校验。
 *
 * <p>为什么要在启动时拒绝而不是运行时报错：默认密钥一旦随代码进仓库，等于所有人都能签发
 * 合法 token，而这个漏洞不会表现为任何功能异常——系统看起来一切正常。把它变成「起不来」，
 * 才会在部署的第一秒暴露，而不是等到被人打进来。</p>
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "im.jwt")
public class JwtProperties implements InitializingBean {

    /** 签名密钥，必须由环境变量 IM_JWT_SECRET 注入，代码内不留可用默认值。 */
    private String secret;

    /** accessToken 有效期（小时）。IM 长连接场景下不做 refresh token，见技术方案。 */
    private long expireHours = 24;

    /** HS384 要求密钥不短于 384 bit，即 48 字节。 */
    private static final int MIN_SECRET_BYTES = 48;

    /** 允许被识别为「占位符」的片段，命中即拒绝启动（忽略大小写）。 */
    private static final Set<String> PLACEHOLDER_FRAGMENTS = Set.of(
            "change-me", "changeme", "please-change", "your-secret", "yoursecret",
            "default-secret", "todo", "placeholder", "example-secret"
    );

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    public void validate() {
        if (secret == null || secret.isBlank()) {
            throw fail("""
                    IM_JWT_SECRET 未设置。
                    JWT 签名密钥为空时系统拒绝启动，因为空密钥意味着任何人都能签发合法 token。
                    请在启动前设置环境变量，例如：
                      Linux/macOS : export IM_JWT_SECRET="$(openssl rand -base64 48)"
                      Windows CMD : set IM_JWT_SECRET=<至少48字节的随机串>
                      PowerShell  : $env:IM_JWT_SECRET="<至少48字节的随机串>"
                    """);
        }

        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < MIN_SECRET_BYTES) {
            throw fail(String.format(
                    "IM_JWT_SECRET 长度不足：当前 %d 字节，HS384 要求至少 %d 字节。"
                            + "请用 openssl rand -base64 48 生成后重新设置。",
                    raw.length, MIN_SECRET_BYTES));
        }

        String normalized = secret.toLowerCase(Locale.ROOT);
        for (String fragment : PLACEHOLDER_FRAGMENTS) {
            if (normalized.contains(fragment)) {
                throw fail(String.format(
                        "IM_JWT_SECRET 看起来是占位符（命中了 '%s'）。"
                                + "占位密钥等同于公开密钥，请换成随机串后再启动。", fragment));
            }
        }

        if (expireHours <= 0) {
            throw fail("im.jwt.expire-hours 必须为正数，当前为 " + expireHours);
        }
    }

    /**
     * 统一出口：先在日志里打一条一眼能看懂的横幅，再抛异常让容器中止。
     *
     * <p>为什么两者都要：只抛异常时，真正的原因会被 Spring 的依赖注入异常链包上三四层，
     * 在几百行堆栈里很难一眼找到；只打日志而不抛异常，进程就会带着空密钥继续跑起来，
     * 那等于校验形同虚设。</p>
     */
    private IllegalStateException fail(String detail) {
        log.error("""

                ============================================================
                 启动中止：JWT 密钥校验未通过
                ------------------------------------------------------------
                {}
                ============================================================
                """, detail);
        return new IllegalStateException("[启动中止] " + detail);
    }
}
