package com.mindsafe.api.config;

import com.mindsafe.api.security.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 配置（JWT 认证 + 角色授权 + CORS + 安全头）
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Value("${mindsafe.cors.allowed-origins:https://yun.gxjugu.com,http://localhost:5173,http://localhost:5174}")
    private String allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(content -> {})
                        .xssProtection(xss -> {})
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 公开端点：登录 + 试用注册 + PIN 登录 + Token 刷新
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/auth/trial/register").permitAll()
                        .requestMatchers("/api/v1/auth/pin-login").permitAll()
                        .requestMatchers("/api/v1/auth/refresh").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/api-docs/**").permitAll()
                        // 家长端 API：内部 token 验证，不走 Spring Security 角色
                        .requestMatchers("/api/v1/parent/**").permitAll()
                        // 管理端 API：仅 ADMIN 角色
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // 平台管理后台：仅 ADMIN 角色
                        .requestMatchers("/api/v1/platform/**").hasRole("ADMIN")
                        // 改密需已登录
                        .requestMatchers("/api/v1/auth/change-password").authenticated()
                        // 学生对话 API 需认证（前端已接入 JWT）
                        .requestMatchers("/api/v1/chat/**").authenticated()
                        // 教师端 API：所有教师角色 + ADMIN
                        .requestMatchers("/api/v1/teacher/**").hasAnyRole("TEACHER", "PSYCH_TEACHER", "CLASS_TEACHER", "ADMIN")
                        // 预警队列 API：所有教师角色 + ADMIN
                        .requestMatchers("/api/v1/alerts/**").hasAnyRole("TEACHER", "PSYCH_TEACHER", "CLASS_TEACHER", "ADMIN")
                        // 会话 API 需认证
                        .requestMatchers("/api/v1/sessions/**").authenticated()
                        // 放松练习 API 需认证
                        .requestMatchers("/api/v1/relaxation/**").authenticated()
                        // 情绪日记 API 需认证
                        .requestMatchers("/api/v1/diary/**").authenticated()
                        // 其余请求放行（语音/TTS 等辅助 API）
                        .anyRequest().permitAll()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
