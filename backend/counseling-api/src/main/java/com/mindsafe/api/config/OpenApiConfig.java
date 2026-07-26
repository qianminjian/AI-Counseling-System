package com.mindsafe.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3.0 文档配置（SpringDoc）
 * <p>
 * 访问地址：/swagger-ui.html（SecurityConfig 已放行）
 * 认证方案：JWT Bearer Token（登录接口获取 token 后点击 Authorize 填入）
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI mindSafeOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("MindSafe AI 心理辅导系统 API")
                        .description("""
                                AI 小学生心理辅导系统商业化 API。
                                
                                ## 认证流程
                                1. 调用 `POST /api/v1/auth/trial/register`（学生试用注册）或 `POST /api/v1/auth/login`（教师/管理员登录）获取 token
                                2. 点击右上角 **Authorize** 按钮，填入 Bearer token
                                3. 即可调用需要认证的接口
                                
                                ## 角色说明
                                - **student**：学生端（对话、会话、放松练习）
                                - **teacher**：教师端（工作台、预警队列、学生档案、通知）
                                - **admin**：管理端（邀请码管理，兼容教师端全部功能）
                                """)
                        .version("v1.0")
                        .contact(new Contact()
                                .name("MindSafe Team")
                                .url("https://mindsafe.app")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                                .name(SECURITY_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT Bearer Token（从登录/注册接口获取）")));
    }
}
