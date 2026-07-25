package com.mindsafe.api.config;

import com.mindsafe.api.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置（JWT 认证）
 * <p>
 * 放行：/api/v1/auth/login、/api/v1/auth/trial/register、/actuator/**、/swagger-ui/**
 * 鉴权：/api/v1/chat/**（学生端，JWT）、/api/v1/teacher/**（教师端，JWT）、
 *       /api/v1/auth/change-password（已登录用户改密）
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 公开端点：登录 + 试用注册
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/auth/trial/register").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/api-docs/**").permitAll()
                        // 改密需已登录
                        .requestMatchers("/api/v1/auth/change-password").authenticated()
                        // 学生对话 API 需认证（前端已接入 JWT）
                        .requestMatchers("/api/v1/chat/**").authenticated()
                        // 教师端 API 需认证
                        .requestMatchers("/api/v1/teacher/**").authenticated()
                        // 预警队列 API 需认证
                        .requestMatchers("/api/v1/alerts/**").authenticated()
                        // 会话 API 需认证
                        .requestMatchers("/api/v1/sessions/**").authenticated()
                        // 放松练习 API 需认证
                        .requestMatchers("/api/v1/relaxation/**").authenticated()
                        // 其余请求放行（语音/TTS 等辅助 API，M1 宽松）
                        .anyRequest().permitAll()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
