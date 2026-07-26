package com.mindsafe.app;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * MindSafe AI 心理辅导系统 - 启动入口
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.mindsafe")
@MapperScan("com.mindsafe.domain.mapper")
@EnableAsync
public class MindSafeApplication {

    public static void main(String[] args) {
        SpringApplication.run(MindSafeApplication.class, args);
    }
}
