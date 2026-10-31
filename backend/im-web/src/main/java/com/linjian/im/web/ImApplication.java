package com.linjian.im.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动类。
 *
 * <p>{@code @EnableScheduling} 是 W7 加的：收件箱兜底清理与在线状态续期两个任务
 * 都依赖它。没有这个注解，{@code @Scheduled} 方法会被静默忽略——
 * 不报错、不告警，只是永远不执行，是最容易漏掉的一类缺陷。</p>
 */
@SpringBootApplication(scanBasePackages = "com.linjian.im")
@MapperScan("com.linjian.im.chat.mapper")
@EnableScheduling
public class ImApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImApplication.class, args);
    }
}
