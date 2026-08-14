package com.mindsafe.api.config;

import com.mindsafe.api.ratelimit.RateLimitInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.service.device.DeviceSecurityService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web MVC 配置：拦截器 + 设备签名参数解析器（99-6）
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;
    private final DeviceSecurityService deviceSecurityService;
    private final ObjectMapper objectMapper;

    public WebMvcConfig(RateLimitInterceptor rateLimitInterceptor,
                        DeviceSecurityService deviceSecurityService,
                        ObjectMapper objectMapper) {
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.deviceSecurityService = deviceSecurityService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        // 99-6：设备上报签名声明式校验（@DeviceSignature 替代手写三 header + 序列化样板）
        resolvers.add(new DeviceSignatureArgumentResolver(deviceSecurityService, objectMapper));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // AUDIT-DEEP-011（P3-04）：公开端点（voiceprint/verify、device report/config）注册 IP 限流
        // F3：注册范围收敛 RouteCatalog.RATE_LIMIT_PATH_PATTERNS（路径知识单一事实源）
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns(RouteCatalog.RATE_LIMIT_PATH_PATTERNS.toArray(String[]::new));
    }
}
