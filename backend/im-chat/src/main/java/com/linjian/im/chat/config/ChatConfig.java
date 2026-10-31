package com.linjian.im.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class ChatConfig {

    /**
     * BCrypt 强度取 10（每次校验约 50-100ms）。这个数字是刻意的取舍：
     * 太低则离线爆破成本低，太高则在登录接口上变成自造 DoS。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
