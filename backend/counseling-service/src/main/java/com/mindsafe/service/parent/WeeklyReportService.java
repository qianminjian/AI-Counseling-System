package com.mindsafe.service.parent;

import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.User;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 家长周报生成服务（doing/89 N-003 步骤 5，AC-89-07）
 * <p>
 * 周报聚合从 ParentController 下沉（N-004 统计口径收敛的前置——本服务与
 * TeacherService.getStats/DataAnalyticsService 共享统计口径的基础）。
 * Controller 仅编排（解析身份 + 归属校验 + 调用本服务）。
 */
@Service
public class WeeklyReportService {

    private final ParentService parentService;

    public WeeklyReportService(ParentService parentService) {
        this.parentService = parentService;
    }

    /** 生成近 7 天周报（学生档案 + 会话统计 + 情绪分布 + 风险等级）。 */
    public Map<String, Object> generate(UUID tenantId, UUID studentUserId) {
        User student = parentService.getStudent(tenantId, studentUserId);
        if (student == null) {
            return null;
        }

        Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        List<CounselingSession> sessions = parentService.getRecentSessions(tenantId, studentUserId, weekAgo);
        List<MessageSummary> studentMessages =
                parentService.getRecentStudentMessages(tenantId, studentUserId, weekAgo);

        Map<String, Long> emotionDist = studentMessages.stream()
                .filter(m -> m.getEmotionLabel() != null && !m.getEmotionLabel().isBlank())
                .collect(Collectors.groupingBy(MessageSummary::getEmotionLabel, Collectors.counting()));

        int maxRisk = sessions.stream()
                .mapToInt(s -> s.getRiskLevelSnapshot() != null ? s.getRiskLevelSnapshot() : 0)
                .max().orElse(0);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("studentNickname", student.getPseudonym());
        report.put("gradeCode", student.getGradeCode());
        report.put("classCode", student.getClassCode());
        report.put("weekStart", weekAgo.toString());
        report.put("sessionCount", sessions.size());
        report.put("totalTurns", sessions.stream()
                .mapToInt(s -> s.getTurnCount() != null ? s.getTurnCount() : 0).sum());
        report.put("emotionDistribution", emotionDist);
        report.put("maxRiskLevel", maxRisk);
        report.put("riskLabel", switch (maxRisk) {
            case 3 -> "需关注";
            case 2 -> "轻度波动";
            case 1 -> "平稳";
            default -> "良好";
        });
        report.put("generatedAt", Instant.now().toString());
        return report;
    }
}
