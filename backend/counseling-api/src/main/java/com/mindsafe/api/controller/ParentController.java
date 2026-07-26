package com.mindsafe.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.UserMapper;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 家长端 API（只读，token 鉴权）
 * <p>
 * 家长通过教师分享的链接访问，无需登录。
 * Token 由教师端生成，包含 studentUserId + 7 天有效期。
 */
@RestController
@RequestMapping("/api/v1/parent")
public class ParentController {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserMapper userMapper;
    private final CounselingSessionMapper sessionMapper;
    private final MessageSummaryMapper messageSummaryMapper;

    public ParentController(JwtTokenProvider jwtTokenProvider,
                            UserMapper userMapper,
                            CounselingSessionMapper sessionMapper,
                            MessageSummaryMapper messageSummaryMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userMapper = userMapper;
        this.sessionMapper = sessionMapper;
        this.messageSummaryMapper = messageSummaryMapper;
    }

    /**
     * 获取学生情绪周报（家长只读）
     * Header: Authorization: Bearer <parent_token>
     */
    @GetMapping("/report")
    public ApiResponse<Map<String, Object>> getWeeklyReport(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.replace("Bearer ", "");

        // 验证 token（复用 JWT 验证逻辑）
        UUID studentUserId;
        try {
            if (!jwtTokenProvider.validateToken(token)) {
                throw new BizException(ErrorCode.UNAUTHORIZED, "链接已过期或无效");
            }
            studentUserId = jwtTokenProvider.getUserId(token);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "链接已过期或无效");
        }

        User student = userMapper.selectById(studentUserId);
        if (student == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "学生不存在");
        }

        UUID tenantId = student.getTenantId();
        Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);

        // 近 7 天会话
        List<CounselingSession> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<CounselingSession>()
                        .eq(CounselingSession::getTenantId, tenantId)
                        .eq(CounselingSession::getStudentUserId, studentUserId)
                        .ge(CounselingSession::getStartedAt, weekAgo)
                        .orderByDesc(CounselingSession::getStartedAt)
        );

        // 近 7 天情绪标签统计
        List<MessageSummary> studentMessages = messageSummaryMapper.selectList(
                new LambdaQueryWrapper<MessageSummary>()
                        .eq(MessageSummary::getTenantId, tenantId)
                        .eq(MessageSummary::getStudentUserId, studentUserId)
                        .eq(MessageSummary::getSenderType, "student")
                        .ge(MessageSummary::getCreatedAt, weekAgo)
        );

        // 情绪分布
        Map<String, Long> emotionDist = studentMessages.stream()
                .filter(m -> m.getEmotionLabel() != null && !m.getEmotionLabel().isBlank())
                .collect(Collectors.groupingBy(MessageSummary::getEmotionLabel, Collectors.counting()));

        // 最高风险等级
        int maxRisk = sessions.stream()
                .mapToInt(s -> s.getRiskLevelSnapshot() != null ? s.getRiskLevelSnapshot() : 0)
                .max().orElse(0);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("studentNickname", student.getPseudonym());
        report.put("gradeCode", student.getGradeCode());
        report.put("classCode", student.getClassCode());
        report.put("weekStart", weekAgo.toString());
        report.put("sessionCount", sessions.size());
        report.put("totalTurns", sessions.stream().mapToInt(s -> s.getTurnCount() != null ? s.getTurnCount() : 0).sum());
        report.put("emotionDistribution", emotionDist);
        report.put("maxRiskLevel", maxRisk);
        report.put("riskLabel", switch (maxRisk) {
            case 3 -> "需关注";
            case 2 -> "轻度波动";
            case 1 -> "平稳";
            default -> "良好";
        });
        report.put("generatedAt", Instant.now().toString());

        return ApiResponse.ok(report);
    }
}
