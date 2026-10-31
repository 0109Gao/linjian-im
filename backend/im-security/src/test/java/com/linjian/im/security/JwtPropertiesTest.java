package com.linjian.im.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 密钥启动校验的单测。这一层是「默认密钥进仓库」这个 P0 的唯一防线，
 * 所以三个分支（空、过短、占位符）都要有独立断言。
 */
class JwtPropertiesTest {

    private static JwtProperties props(String secret) {
        JwtProperties p = new JwtProperties();
        p.setSecret(secret);
        return p;
    }

    @Test
    @DisplayName("密钥为空时拒绝启动")
    void blankSecret_rejected() {
        assertThatThrownBy(() -> props("").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IM_JWT_SECRET 未设置");

        assertThatThrownBy(() -> props(null).validate())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("密钥短于 HS384 要求的 48 字节时拒绝启动")
    void shortSecret_rejected() {
        assertThatThrownBy(() -> props("short-secret-32-bytes-only-abcdef").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("长度不足");
    }

    @Test
    @DisplayName("密钥含占位符片段时拒绝启动，即使长度合法")
    void placeholderSecret_rejected() {
        String placeholder = "please-change-me-in-production-0123456789abcdefghijkl";
        assertThatThrownBy(() -> props(placeholder).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("占位符");
    }

    @Test
    @DisplayName("合法随机串通过校验")
    void validSecret_accepted() {
        String valid = "t3Kq9vLm2Xp8Rz5Nw7Yb4Hc6Jd1Fs0Ga9Ue2It5Ox8Az3Mn6Qr4Ws7Bk1Lp0Dv9Cf";
        assertThatCode(() -> props(valid).validate()).doesNotThrowAnyException();
    }
}
