package com.mindsafe.service.session;

import com.mindsafe.domain.entity.CounselingSession;

import java.util.UUID;

/**
 * 会话访问与归属校验公共服务（T4 批次A：消灭 Controller 手写租户条件自觉）。
 * <p>
 * 租户条件强制内置在实现内，调用方无法漏写——从结构上杜绝跨租户越权（SEC-001 对齐），
 * 取代「Controller 直查 Mapper + 手工 eq(tenantId)」的贫血模型模式。
 */
public interface SessionAccessService {

    /** 按租户+会话 ID 查询；不存在或非本租户返回 null */
    CounselingSession getTenantSession(UUID tenantId, UUID sessionId);

    /** 会话归属校验：会话存在且属于该租户 */
    boolean sessionBelongsToTenant(UUID tenantId, UUID sessionId);
}
