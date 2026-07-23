package com.mindsafe.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 配置（M1 开发阶段）
 * <p>
 * M1：放行所有 API（无 JWT 验证），仅禁用 CSRF + 设置无状态会话。
 * M2+：接入 JWT 过滤器、角色鉴权、CORS 策略。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // M1 开发阶段：全部放行
                        .anyRequest().permitAll()
                );

        return http.build();
    }
}
