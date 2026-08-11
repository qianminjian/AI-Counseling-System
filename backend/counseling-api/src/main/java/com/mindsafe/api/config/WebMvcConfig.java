package com.mindsafe.api.config;

import com.mindsafe.api.ratelimit.RateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册拦截器
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    public WebMvcConfig(RateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // AUDIT-DEEP-011（P3-04）：公开端点（voiceprint/verify、device report/config）注册 IP 限流
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/v1/chat/**", "/api/v1/tts/synthesize",
                        "/api/v1/voiceprint/verify", "/api/v1/device/report/**", "/api/v1/device/config/pull");
    }
}
