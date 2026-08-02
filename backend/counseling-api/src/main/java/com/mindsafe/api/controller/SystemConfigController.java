package com.mindsafe.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.api.config.SystemConfigProperties;
import com.mindsafe.common.dto.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 系统配置公开 API（CFG-001 配置统一纳管）
 * <p>
 * GET /api/v1/system/config — 前端启动时拉取运行时配置，覆盖本地默认值。
 * <p>
 * 设计要点：
 * - permitAll：无需 JWT（前端在登录前就需要配置，如声纹模式判断）
 * - Cache-Control: public, max-age=300（5 分钟浏览器缓存，减少请求）
 * - 仅返回前端需要的业务参数，不含密钥/服务 URL
 * - 配置源：application.yml mindsafe.system-config 子树
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemConfigController {

    private final SystemConfigProperties properties;
    private final ObjectMapper objectMapper;

    public SystemConfigController(SystemConfigProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取前端运行时配置（公开，无需登录）
     * <p>
     * 前端 main.jsx 启动时调用，覆盖 config/*.ts 中的本地默认值。
     * 接口失败时前端 fallback 到本地默认值，不阻塞启动。
     */
    @GetMapping("/config")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getConfigWithCache() {
        ApiResponse<Map<String, Object>> body = getConfig();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(300, TimeUnit.SECONDS).cachePublic())
                .body(body);
    }

    /**
     * 构建配置响应（供测试直接调用，不经过 HTTP 层）
     */
    ApiResponse<Map<String, Object>> getConfig() {
        Map<String, Object> configMap = buildConfigMap();
        return ApiResponse.ok(configMap);
    }

    private Map<String, Object> buildConfigMap() {
        Map<String, Object> map = new LinkedHashMap<>();

        if (properties.getVoiceprint() != null) {
            map.put("voiceprint", objectMapper.convertValue(properties.getVoiceprint(), Map.class));
        }
        if (properties.getWakeWord() != null) {
            map.put("wakeWord", objectMapper.convertValue(properties.getWakeWord(), Map.class));
        }
        if (properties.getTts() != null) {
            map.put("tts", objectMapper.convertValue(properties.getTts(), Map.class));
        }
        if (properties.getGuideScripts() != null) {
            map.put("guideScripts", objectMapper.convertValue(properties.getGuideScripts(), Map.class));
        }

        return map;
    }
}
