package com.mindsafe.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.service.conversation.ConversationService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 学生端会话管理 API（对齐 design/16 §3）
 * <p>
 * 功能：会话历史列表 / 结束会话+满意度评价
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final CounselingSessionMapper sessionMapper;
    private final ConversationService conversationService;

    public SessionController(CounselingSessionMapper sessionMapper,
                             ConversationService conversationService) {
        this.sessionMapper = sessionMapper;
        this.conversationService = conversationService;
    }

    /** 会话历史列表 */
    @GetMapping
    public ApiResponse<List<SessionHistoryVO>> getSessionHistory(
            Authentication auth,
            @RequestParam(defaultValue = "20") int limit) {
        TenantContext ctx = extractContext(auth);
        List<CounselingSession> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, ctx.tenantId())
                        .eq(CounselingSession::getStudentUserId, ctx.userId())
                        .orderByDesc(CounselingSession::getStartedAt)
                        .last("LIMIT " + Math.min(limit, 50))
        );
        List<SessionHistoryVO> voList = sessions.stream()
                .map(s -> new SessionHistoryVO(
                        s.getSessionId(), s.getStartedAt(), s.getEndedAt(),
                        s.getSessionStatus(), s.getRiskLevelSnapshot(),
                        s.getSatisfactionRating()
                ))
                .toList();
        return ApiResponse.ok(voList);
    }

    /** 结束会话 + 满意度评价 */
    @PostMapping("/{id}/close")
    public ApiResponse<Void> closeSession(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body,
            Authentication auth) {
        TenantContext ctx = extractContext(auth);

        // 结束会话（SEC-001：传当前用户 ID 做会话归属校验）
        conversationService.endSession(ctx.tenantId(), ctx.userId(), id);

        // 保存满意度评价（如果有）
        if (body != null && body.containsKey("rating")) {
            int rating = ((Number) body.get("rating")).intValue();
            String comment = (String) body.getOrDefault("comment", null);

            CounselingSession update = new CounselingSession();
            update.setSessionId(id);
            update.setSatisfactionRating(rating);
            update.setSatisfactionComment(comment);
            update.setUpdatedAt(Instant.now());
            sessionMapper.updateById(update);
        }

        return ApiResponse.ok(null);
    }

    private TenantContext extractContext(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof TenantContext ctx)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return ctx;
    }

    /** 会话历史 VO */
    public record SessionHistoryVO(
            UUID sessionId, Instant startedAt, Instant endedAt,
            String status, Integer riskLevel, Integer satisfactionRating
    ) {}
}
