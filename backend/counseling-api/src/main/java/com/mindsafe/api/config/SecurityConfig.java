package com.mindsafe.api.config;

import com.mindsafe.api.security.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
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
    private final Environment environment;

    @Value("${mindsafe.cors.allowed-origins:https://yun.gxjugu.com,http://localhost:5173,http://localhost:5174}")
    private String allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, Environment environment) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.environment = environment;
    }

    private boolean isProd() {
        return Arrays.asList(environment.getActiveProfiles()).contains("prod");
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .headers(headers -> {
                    headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(content -> {})
                        .xssProtection(xss -> {});
                    headers.contentSecurityPolicy(csp -> csp
                        .policyDirectives("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; connect-src 'self'; font-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")
                    );
                })
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // ASYNC/ERROR 分发放行：SSE 流式响应经 ASYNC 二次分发时 SecurityContext 不传播，
                        // 初始 REQUEST 分发已完成鉴权，二次分发再拦会掐断流（IT: sendRedRiskMessage 回归）
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR).permitAll()
                        // ─── 公开端点（无需 JWT）───
                        // 认证流程入口
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/auth/trial/register").permitAll()
                        .requestMatchers("/api/v1/auth/pin-login").permitAll()
                        .requestMatchers("/api/v1/auth/refresh").permitAll()
                        // 企微 OAuth 回调（无 JWT，靠 code 换 token）
                        .requestMatchers("/api/v1/auth/wecom/**").permitAll()
                        // 监护人同意确认（SMS 链接触发，无 JWT，靠 confirmToken 校验）
                        .requestMatchers("/api/v1/auth/guardian-consent/confirm").permitAll()
                        // 家长端 API（内部 parentToken 验证，不走 Spring Security 角色）
                        .requestMatchers("/api/v1/parent/**").permitAll()
                        // 健康检查（Docker/Nginx 探针）
                        .requestMatchers("/actuator/health").permitAll()
                        // WebSocket（握手后内部鉴权）
                        .requestMatchers("/ws/**").permitAll()
                        // ─── 环境受控端点 ───
                        // Actuator：生产仅暴露 health，开发全开
                        .requestMatchers("/actuator/**").access(
                                (authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(!isProd()))
                        // Swagger：生产环境完全禁止访问
                        .requestMatchers("/swagger-ui/**", "/api-docs/**").access(
                                (authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(!isProd()))
                        // ─── 角色授权 ───
                        // 管理端 + 平台后台：仅 ADMIN
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/platform/**").hasRole("ADMIN")
                        // 知识库管理：仅 ADMIN（内容审核/入库为管理职责）
                        .requestMatchers("/api/v1/knowledge/**").hasRole("ADMIN")
                        // 教师端 + 预警 + 数据分析：教师角色 + ADMIN
                        .requestMatchers("/api/v1/teacher/**").hasAnyRole("TEACHER", "PSYCH_TEACHER", "CLASS_TEACHER", "ADMIN")
                        .requestMatchers("/api/v1/alerts/**").hasAnyRole("TEACHER", "PSYCH_TEACHER", "CLASS_TEACHER", "ADMIN")
                        .requestMatchers("/api/v1/analytics/**").hasAnyRole("TEACHER", "PSYCH_TEACHER", "CLASS_TEACHER", "ADMIN")
                        // ─── 兜底：其余全部需认证（TTS/Voice/Chat/Session/Diary/Relaxation/Auth 子端点等）───
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
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
