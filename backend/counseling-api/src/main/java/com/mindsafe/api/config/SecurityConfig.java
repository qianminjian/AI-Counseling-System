package com.mindsafe.api.config;

import com.mindsafe.api.security.JwtAuthenticationFilter;
import com.mindsafe.api.security.RoleConstants;
import com.mindsafe.common.dto.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
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
                // B-07（doing/98）：安全头部合并为单次 .headers() 配置（原 doing/90 P3-2 叠加式二次
                // .headers() 与首次重复，已删除；frameOptions/contentTypeOptions/CSP 均由首次覆盖）
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // BUG-A-TOKEN-01 语义收口（2026-08-12，UI-TEST-016）：未认证 → 401（前端 authFetch
                // 刷新/登出链依赖），已认证无权限 → 403（前端区分展示）；此前默认 entry point 对
                // 未认证请求返回 403，前端不触发刷新链，会话过期只能手动刷新页面。
                .exceptionHandling(ex -> ex
                        // 审计 F6：非 controller 路径统一经 ErrorResponseWriter 按 ApiResponse 契约
                        // {code,message,data,timestamp} 序列化（原手拼 success 字段形状，前端需多形状兼容）
                        .authenticationEntryPoint((request, response, authException) ->
                                ErrorResponseWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        ErrorCode.UNAUTHORIZED, "未认证或登录已过期"))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                ErrorResponseWriter.write(response, HttpServletResponse.SC_FORBIDDEN,
                                        ErrorCode.FORBIDDEN, "无权限访问"))
                )
                .authorizeHttpRequests(auth -> auth
                        // ASYNC/ERROR 分发放行：SSE 流式响应经 ASYNC 二次分发时 SecurityContext 不传播，
                        // 初始 REQUEST 分发已完成鉴权，二次分发再拦会掐断流（IT: sendRedRiskMessage 回归）
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR).permitAll()
                        // ─── 公开端点（无需 JWT）：单一事实源 RouteCatalog.PUBLIC_PATTERNS（审计 F3）───
                        // 认证流程入口/设备上报/企微回调/家长端/toC 注册登录/平台登录/健康检查/指标/WebSocket，
                        // 各端点防护措施见 RouteCatalog 注释（doing/90 P3-3 公开端点审计 + AUD-005 校正）
                        .requestMatchers(RouteCatalog.PUBLIC_PATTERNS.toArray(String[]::new)).permitAll()
                        // toC 家庭版：档案/设备等登录态（ROLE_TOC_PARENT；注册/登录已在上方 permitAll）
                        .requestMatchers("/api/v1/toc/**").hasRole("TOC_PARENT")
                        // ─── 环境受控端点 ───
                        // Actuator：生产仅暴露 health，开发全开
                        .requestMatchers("/actuator/**").access(
                                (authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(!isProd()))
                        // Swagger：生产环境完全禁止访问
                        .requestMatchers("/swagger-ui/**", "/api-docs/**").access(
                                (authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(!isProd()))
                        // ─── 角色授权 ───
                        // Prompt 管理（ADMIN-P1-02，M7）：admin-web 平台角色消费（code-review H1）
                        // ⚠️ 必须置于 /api/v1/admin/** 兜底之前（Spring Security 按声明顺序匹配；
                        //    2026-08-11 BUG-A-001：原顺序在后导致平台 super_admin 403 + 前端登出死循环）
                        .requestMatchers("/api/v1/admin/prompts/**")
                                .hasAnyRole("ADMIN", "PLATFORM_SUPER_ADMIN", "PLATFORM_OPS_ADMIN")
                        // 管理端 + 平台后台：仅业务 ADMIN
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // 平台后台（ADMIN-P0-03；P0 backlog ⑤ 双轨收敛 2026-08-10）：平台总览已迁 admin-web，
                        // 移除业务 ADMIN 过渡角色，平台端点仅平台四角色（PLATFORM_ token）
                        // 配置修改（ADMIN-P1-01）：仅超级管理员（平台 super_admin）
                        .requestMatchers(HttpMethod.POST, "/api/v1/platform/config/**")
                                .hasAnyRole("PLATFORM_SUPER_ADMIN")
                        .requestMatchers("/api/v1/platform/**")
                                .hasAnyRole("PLATFORM_SUPER_ADMIN", "PLATFORM_OPS_ADMIN", "PLATFORM_FINANCE_ADMIN", "PLATFORM_AUDIT")
                        // M8 高危写操作（ADMIN-P1-05）：转派/强制关闭仅 ops/super（audit 只读，code-review M1）
                        // 注意：必须置于 /api/v1/ops/** 兜底之前（Spring Security 按声明顺序匹配）
                        .requestMatchers(HttpMethod.POST, "/api/v1/ops/risk/**")
                                .hasAnyRole("PLATFORM_SUPER_ADMIN", "PLATFORM_OPS_ADMIN")
                        // M3 手动降级切换（ADMIN-P2-01）：仅 ops/super（audit 只读）
                        .requestMatchers(HttpMethod.POST, "/api/v1/ops/degradation/**")
                                .hasAnyRole("PLATFORM_SUPER_ADMIN", "PLATFORM_OPS_ADMIN")
                        // M2 告警确认（ADMIN-P1-08）：ack 仅 ops/super（audit 只读，置于 /ops/** 兜底前）
                        .requestMatchers(HttpMethod.POST, "/api/v1/ops/alerts/**")
                                .hasAnyRole("PLATFORM_SUPER_ADMIN", "PLATFORM_OPS_ADMIN")
                        // 用量报表（ADMIN-P3-02）：finance/audit 只读可访问（code-review H2，§7.4）
                        .requestMatchers(HttpMethod.GET, "/api/v1/ops/usage/**")
                                .hasAnyRole("PLATFORM_SUPER_ADMIN", "PLATFORM_OPS_ADMIN", "PLATFORM_FINANCE_ADMIN", "PLATFORM_AUDIT")
                        // 运维域（ADMIN-P0-05/06 新增：服务拓扑/指标/告警，仅平台角色）
                        .requestMatchers("/api/v1/ops/**")
                                .hasAnyRole("PLATFORM_SUPER_ADMIN", "PLATFORM_OPS_ADMIN", "PLATFORM_AUDIT")
                        // 知识库管理：仅 ADMIN（内容审核/入库为管理职责）
                        .requestMatchers("/api/v1/knowledge/**").hasRole("ADMIN")
                        // 教师端 + 预警 + 数据分析：教师角色 + ADMIN
                        // BUG-T-RC-01（2026-08-12，UI-TEST-013）：补 ROLE_HEAD_TEACHER——班主任（head_teacher）
                        // 既有账号体系角色名，此前缺失导致登录后全接口 403；服务层已按班主任语义裁剪数据。
                        // 审计 F4：教师五角色单源（RoleConstants 派生；防双维护漏配回归）
                        .requestMatchers("/api/v1/teacher/**").hasAnyRole(RoleConstants.teacherAlertAuthorities())
                        .requestMatchers("/api/v1/alerts/**").hasAnyRole(RoleConstants.teacherAlertAuthorities())
                        .requestMatchers("/api/v1/analytics/**").hasAnyRole(RoleConstants.teacherAlertAuthorities())
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
