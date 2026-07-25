package com.mindsafe.service.audit;

import com.mindsafe.domain.entity.AuditLog;
import com.mindsafe.domain.mapper.AuditLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 审计日志服务（对齐 design/16 §5 审计与日志）
 * <p>
 * 异步记录敏感操作：登录/查看学生档案/导出/配置变更/预警处理。
 * 不阻塞主业务流程。
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogMapper auditLogMapper;

    public AuditLogService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 记录审计日志（异步，不影响主流程）
     */
    @Async
    public void log(UUID tenantId, UUID userId, String action,
                    String resourceType, UUID resourceId, String detail) {
        try {
            AuditLog auditLog = AuditLog.create(tenantId, userId, action, resourceType, resourceId, detail);
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            // 审计日志失败不影响业务
            log.warn("审计日志写入失败: action={}, resource={}:{}", action, resourceType, resourceId, e);
        }
    }

    /** 简化版：无 resourceId */
    @Async
    public void log(UUID tenantId, UUID userId, String action, String resourceType) {
        log(tenantId, userId, action, resourceType, null, null);
    }
}
