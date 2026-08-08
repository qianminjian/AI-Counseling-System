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
 * 注册 {@link PaginationInnerInterceptor}（AUD-043）：列表查询已统一用 {@code Page} 分页，
 * 存量仅剩「取单条/上限查询」仍用 {@code .last("LIMIT ...")}（值均为常量，无注入面；
 * 位置：TrialAuthService/ParentAuthService/WeComOAuthService/PromptVersionService/
 * StudentProfileService/TeacherQualityService，后续批次逐处收敛）。
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
