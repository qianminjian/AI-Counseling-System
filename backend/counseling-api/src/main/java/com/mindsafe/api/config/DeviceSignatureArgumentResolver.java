package com.mindsafe.api.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.service.device.DeviceSecurityService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.nio.charset.StandardCharsets;

/**
 * 设备上报签名解析器（99-6，2026-08-14）——声明式签名校验 seam。
 * <p>
 * 为标注 {@link DeviceSignature} 的参数：读取原始请求体（canonical body = 原始字节，
 * 固件签名即同一字节序列，比 record 重序列化更精确）→ 提取 deviceCode → 按
 * signature-mode 执行 HMAC 校验 → 反序列化为参数类型返回。
 * 校验通过/模式放行后，参数由本解析器直接提供（替代 @RequestBody 语义）。
 */
public class DeviceSignatureArgumentResolver implements HandlerMethodArgumentResolver {

    private final DeviceSecurityService securityService;
    private final ObjectMapper objectMapper;

    public DeviceSignatureArgumentResolver(DeviceSecurityService securityService, ObjectMapper objectMapper) {
        this.securityService = securityService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(DeviceSignature.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) throws Exception {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少请求上下文");
        }
        String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode node;
        try {
            node = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            // M1：空/畸形 body 转 400（对齐 GlobalExceptionHandler 的 HttpMessageNotReadableException 语义，避免落兜底 500）
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体格式错误");
        }
        String deviceCode = node.path("deviceCode").asText(null);

        securityService.enforceSignature(deviceCode, body,
                request.getHeader("X-Device-Timestamp"),
                request.getHeader("X-Device-Nonce"),
                request.getHeader("X-Device-Signature"));

        try {
            return objectMapper.readValue(body, parameter.getParameterType());
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体格式错误");
        }
    }
}
