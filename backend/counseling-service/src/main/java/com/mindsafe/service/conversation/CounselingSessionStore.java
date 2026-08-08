package com.mindsafe.service.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 会话 DB 读写仓储（BA-11：DB 侧会话仓储，编排器不再直连 Mapper）。
 * <p>
 * 从 ConversationServiceImpl 抽取（原 10+ 处 DB 访问散落编排层），职责：
 * <ul>
 *   <li>会话增改查透传（insert/updateById/findById）</li>
 *   <li>escalated 判定（查询失败降级 false，决策模型层有风险信号兜底）</li>
 *   <li>历史分页查询（租户+学生双重条件内置，limit 上限 50 防大分页拖库）</li>
 *   <li>归属校验查询（租户+学生+会话三重条件，SEC-001）</li>
 * </ul>
 * 会话表读写的条件组装全部收口于此，获得独立测试面（仓储零测试问题消除）。
 */
@Service
public class CounselingSessionStore {

    private static final Logger log = LoggerFactory.getLogger(CounselingSessionStore.class);

    /** 历史查询 limit 上限（防大分页拖库；原 ConversationServiceImpl 内 Math.min(limit, 50) 内置收口） */
    private static final int MAX_HISTORY_LIMIT = 50;

    private final CounselingSessionMapper sessionMapper;

    public CounselingSessionStore(CounselingSessionMapper sessionMapper) {
        this.sessionMapper = sessionMapper;
    }

    /** 新增会话（createSession 落库） */
    public void insert(CounselingSession entity) {
        sessionMapper.insert(entity);
    }

    /** 部分字段更新（风险快照/升级/版本标记/结束/评价等，非空字段覆盖） */
    public void updateById(CounselingSession update) {
        sessionMapper.updateById(update);
    }

    /** 按主键查询（statePath 读取 / escalated 判定） */
    public CounselingSession findById(UUID sessionId) {
        return sessionMapper.selectById(sessionId);
    }

    /**
     * 会话是否已 escalated（红色风险接管）；查询失败降级 false，不阻断暖场
     * （决策模型层有风险信号兜底）。
     */
    public boolean isEscalated(UUID sessionId) {
        try {
            CounselingSession entity = sessionMapper.selectById(sessionId);
            return entity != null && "escalated".equals(entity.getSessionStatus());
        } catch (Exception e) {
            log.warn("nudge: 查询会话状态失败: sessionId={}", sessionId, e);
            return false;
        }
    }

    /**
     * 会话历史（租户+学生双重条件内置 SEC-001，startedAt 倒序，limit 上限 50）。
     */
    public List<CounselingSession> findHistory(UUID tenantId, UUID studentUserId, int limit) {
        Page<CounselingSession> pageResult = sessionMapper.selectPage(
                new Page<>(1, Math.min(limit, MAX_HISTORY_LIMIT), false),
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, tenantId)
                        .eq(CounselingSession::getStudentUserId, studentUserId)
                        .orderByDesc(CounselingSession::getStartedAt));
        return pageResult.getRecords();
    }

    /**
     * 会话归属校验查询（租户+学生+会话三重条件；无匹配返回 null，调用方拒绝非持有人操作）。
     */
    public CounselingSession findOwned(UUID tenantId, UUID studentUserId, UUID sessionId) {
        return sessionMapper.selectOne(
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, tenantId)
                        .eq(CounselingSession::getStudentUserId, studentUserId)
                        .eq(CounselingSession::getSessionId, sessionId));
    }
}
