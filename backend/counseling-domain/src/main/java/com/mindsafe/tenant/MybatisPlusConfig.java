package com.mindsafe.tenant;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件装配（P-02 多租户行隔离）
 * <p>
 * 注册 {@link TenantLineInnerInterceptor}，为业务表 SQL 自动追加租户条件，构成隔离纵深防线。
 * 由 counseling-app 的 {@code @ComponentScan("com.mindsafe")} 扫描生效。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public TenantLineHandler mindSafeTenantLineHandler() {
        return new MindSafeTenantLineHandler();
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(TenantLineHandler tenantLineHandler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(tenantLineHandler));
        return interceptor;
    }
}
