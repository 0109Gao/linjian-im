package com.linjian.im.common.json;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 让全局 ObjectMapper 采用 Long 安全序列化策略。REST 与 STOMP 的 JSON 编解码共用同一个
 * ObjectMapper，因此这一处配置同时覆盖两条通道。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longSafeCustomizer() {
        LongSafeSerializer serializer = new LongSafeSerializer();
        return builder -> builder
                .serializerByType(Long.class, serializer)
                .serializerByType(Long.TYPE, serializer);
    }
}
