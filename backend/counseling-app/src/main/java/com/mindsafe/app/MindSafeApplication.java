package com.mindsafe.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * MindSafe AI 心理辅导系统 - 启动入口
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.mindsafe")
public class MindSafeApplication {

    public static void main(String[] args) {
        SpringApplication.run(MindSafeApplication.class, args);
    }
}
