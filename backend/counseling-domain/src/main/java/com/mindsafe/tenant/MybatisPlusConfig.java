package com.mindsafe.tenant;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件装配（P-02 多租户行隔离）
 * <p>
 * 注册 {@link TenantLineInnerInterceptor}，为业务表 SQL 自动追加租户条件，构成 隔离纵深防线。
 * 注册 {@link PaginationInnerInterceptor}（AUD-043）：业务代码统一用 {@code Page} 分页，
 * 不再通过 {@code .last("LIMIT ...")} 字符串拼接（值虽已钳制无注入面，但用法不安全）。
 * 插件顺序：租户行拦截器必须在分页插件之前（官方要求，分页 SQL 生成依赖租户条件先行）。
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
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }
}
