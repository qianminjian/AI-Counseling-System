package com.mindsafe.service.audit;

import com.mindsafe.domain.entity.AuditLog;
import com.mindsafe.domain.mapper.AuditLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 审计日志服务（COMP-006 增强：敏感操作全覆盖 + 请求上下文自动捕获）
 * <p>
 * 异步记录敏感操作：登录/查看学生档案/导出/配置变更/预警处理/Prompt版本管理。
 * 自动捕获 IP 哈希 + UserAgent，不阻塞主业务流程。
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogMapper auditLogMapper;

    public AuditLogService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 记录审计日志（异步，自动捕获请求上下文）
     */
    @Async
    public void log(UUID tenantId, UUID userId, String action,
                    String resourceType, UUID resourceId, String detail) {
        try {
            AuditLog auditLog = AuditLog.create(tenantId, userId, action, resourceType, resourceId, detail);
            // COMP-006: 自动捕获请求上下文
            captureRequestContext(auditLog);
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            log.warn("审计日志写入失败: action={}, resource={}:{}", action, resourceType, resourceId, e);
        }
    }

    /** 简化版：无 resourceId */
    @Async
    public void log(UUID tenantId, UUID userId, String action, String resourceType) {
        log(tenantId, userId, action, resourceType, null, null);
    }

    /**
     * COMP-006: 从当前请求上下文捕获 IP 哈希 + UserAgent
     */
    private void captureRequestContext(AuditLog auditLog) {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return;
            HttpServletRequest request = attrs.getRequest();

            // IP 哈希（SHA-256 前 16 位，满足审计追溯但不暴露明文 IP）
            String ip = extractClientIp(request);
            if (ip != null) {
                auditLog.setIpHash(sha256Short(ip));
            }

            // UserAgent
            String ua = request.getHeader("User-Agent");
            if (ua != null) {
                auditLog.setUserAgent(ua.length() > 200 ? ua.substring(0, 200) : ua);
            }
        } catch (Exception e) {
            // 请求上下文捕获失败不影响审计日志主体
            log.debug("请求上下文捕获失败", e);
        }
    }

    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }

    private String sha256Short(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return "unknown";
        }
    }
}
