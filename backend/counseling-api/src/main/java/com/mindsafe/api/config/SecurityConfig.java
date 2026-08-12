package com.mindsafe.api.config;

import com.mindsafe.api.security.JwtAuthenticationFilter;
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
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // BUG-A-TOKEN-01 语义收口（2026-08-12，UI-TEST-016）：未认证 → 401（前端 authFetch
                // 刷新/登出链依赖），已认证无权限 → 403（前端区分展示）；此前默认 entry point 对
                // 未认证请求返回 403，前端不触发刷新链，会话过期只能手动刷新页面。
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":20001,\"message\":\"未认证或登录已过期\",\"success\":false}");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":20002,\"message\":\"无权限访问\",\"success\":false}");
                        })
                )
                // doing/90 P3-2（2026-08-11）：安全头部——防点击劫持/XSS/MIME 嗅探
                .headers(headers -> headers
                    .frameOptions(f -> f.deny())
                    .contentTypeOptions()
                )
                .authorizeHttpRequests(auth -> auth
                        // ASYNC/ERROR 分发放行：SSE 流式响应经 ASYNC 二次分发时 SecurityContext 不传播，
                        // 初始 REQUEST 分发已完成鉴权，二次分发再拦会掐断流（IT: sendRedRiskMessage 回归）
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR).permitAll()
                        // ─── 公开端点（无需 JWT）───
                        // 认证流程入口
                        // doing/90 P3-3（2026-08-11，公开端点审计）：以下 11 个端点无 JWT 认证，各防护措施见注释
                        .requestMatchers("/api/v1/auth/login").permitAll()              // 登录入口（限流+锁定 LoginLockoutService）
                        .requestMatchers("/api/v1/auth/trial/register").permitAll()     // 试注册（限流）
                        .requestMatchers("/api/v1/auth/pin-login").permitAll()           // PIN 登录（限流）
                        // 声纹登录（本地声纹比对通过后凭设备凭证换 token，凭证在端点内校验）
                        .requestMatchers("/api/v1/auth/voice-login").permitAll()         // 声纹登录（IP+embedding 双限流 SEC-007）
                        // 声纹双模式：config 公开查询模式，verify 公开接收 embedding 比对签发 token
                        .requestMatchers("/api/v1/voiceprint/config").permitAll()        // 声纹配置（只读）
                        .requestMatchers("/api/v1/voiceprint/verify").permitAll()        // 声纹验证（IP+embedding 双限流+IP 300/min）
                        // 声纹登录引导语 TTS（白名单文本，无需认证）
                        .requestMatchers("/api/v1/tts/login-prompt").permitAll()          // TTS 登录引导
                        // TTS 音色人设列表（登录前选音色/配置需要，公开只读）
                        .requestMatchers("/api/v1/tts/personas").permitAll()              // TTS 人设列表
                        // 前端运行时配置（CFG-001，登录前即需拉取，如声纹模式判断）
                        .requestMatchers("/api/v1/system/config").permitAll()             // 系统配置（CFG-001 公开只读）
                        .requestMatchers("/api/v1/auth/refresh").permitAll()              // token 刷新（需旧 token 签名校验）
                        // CFG-001（V39）：无屏终端设备上报/脱敏查询（设备端无 JWT，扫码入口匿名可查）；
                        // 绑定类端点（bind-code/bind/unbind）默认需登录态
                        // P0-1/AD-002：设备 report 通道暂回 permitAll（固件侧 HMAC 签名未就绪，
                        // 见 frozen/73 §九「设备签名鉴权」跟踪项）。reportOnline 保留公开（首次上线必须），
                        // DeviceSecurityService.validateToken 已就绪待 NST-HW-02 固件二期对接。
                        .requestMatchers("/api/v1/device/report/**").permitAll()
                        .requestMatchers("/api/v1/device/*/info").permitAll()
                        .requestMatchers("/api/v1/device/*/status").permitAll()
                        .requestMatchers("/api/v1/device/config/pull").permitAll()
                        // 企微 OAuth 回调（无 JWT，靠 code 换 token）
                        .requestMatchers("/api/v1/auth/wecom/**").permitAll()
                        // 监护人同意确认：要求学生已登录会话内确认（GuardianConsentGate 流程），
                        // AUD-005 校正：原注释声称 confirmToken 免登录流程从未实现，confirm 端点依赖 JWT 身份解包，permitAll 已删除
                        // 家长端 API（内部 parentToken 验证，不走 Spring Security 角色）
                        .requestMatchers("/api/v1/parent/**").permitAll()
                        // toC 家庭版（doing/85 TOC-001）：验证码注册/登录匿名，档案/设备等登录态（ROLE_TOC_PARENT）
                        .requestMatchers("/api/v1/toc/auth/send-code", "/api/v1/toc/auth/register", "/api/v1/toc/auth/login").permitAll()
                        .requestMatchers("/api/v1/toc/**").hasRole("TOC_PARENT")
                        // 平台管理员登录（ADMIN-P0-02：独立登录端点，R-8）
                        .requestMatchers("/api/v1/platform/auth/login").permitAll()
                        // 健康检查（Docker/Nginx 探针）
                        .requestMatchers("/actuator/health").permitAll()
                        // Prometheus 指标抓取（OPS-MON-003，2026-08-10）：容器 internal 网络内可达（不经公网），
                        // 生产放行 /actuator/prometheus——否则抓取 403、指标看板/告警规则无数据
                        .requestMatchers("/actuator/prometheus").permitAll()
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
                        .requestMatchers("/api/v1/teacher/**").hasAnyRole("TEACHER", "PSYCH_TEACHER", "CLASS_TEACHER", "HEAD_TEACHER", "ADMIN")
                        .requestMatchers("/api/v1/alerts/**").hasAnyRole("TEACHER", "PSYCH_TEACHER", "CLASS_TEACHER", "HEAD_TEACHER", "ADMIN")
                        .requestMatchers("/api/v1/analytics/**").hasAnyRole("TEACHER", "PSYCH_TEACHER", "CLASS_TEACHER", "HEAD_TEACHER", "ADMIN")
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
