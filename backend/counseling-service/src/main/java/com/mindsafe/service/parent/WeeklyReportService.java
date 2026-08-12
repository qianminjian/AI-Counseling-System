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
        // OBS-P-03-01（2026-08-12）：AI 建议区块——规则化生成（design/12 §4.3，不调 LLM 保稳定低成本）
        report.put("aiAdvice", buildAdvice(maxRisk, sessions.size(), emotionDist));
        report.put("generatedAt", Instant.now().toString());
        return report;
    }

    /**
     * 周报建议文案（规则化：风险等级主导 + 情绪分布/对话频度补充，非 LLM 生成）。
     * 口径与 design/12 §4.3 对齐：风险状态 + 鼓励沟通 + 具体关注点。
     */
    private static String buildAdvice(int maxRisk, int sessionCount, Map<String, Long> emotionDist) {
        String riskPart = switch (maxRisk) {
            case 3 -> "本周孩子出现过需要重点关注的情绪波动，建议近期多陪伴沟通，如有需要可联系学校心理老师。";
            case 2 -> "本周孩子情绪有轻度波动，建议留意其状态变化，保持温和沟通。";
            case 1 -> "本周孩子情绪整体平稳，偶有起伏属正常，继续保持日常交流即可。";
            default -> "本周孩子情绪状态良好，保持了积极的表达习惯，非常棒！";
        };
        // 高频情绪提示（仅当存在统计且非良好基线时附加）
        String emotionPart = "";
        if (!emotionDist.isEmpty()) {
            Map.Entry<String, Long> top = emotionDist.entrySet().stream()
                    .max(java.util.Map.Entry.comparingByValue())
                    .orElse(null);
            if (top != null && !"happy".equals(top.getKey()) && !"calm".equals(top.getKey())) {
                emotionPart = " 孩子本周表达最多的情绪是「" + top.getKey() + "」，可以多聊聊感受背后的原因。";
            }
        }
        String sessionPart = sessionCount == 0
                ? " 本周暂无对话记录，鼓励孩子和波波聊聊天。"
                : " 本周共进行 " + sessionCount + " 次倾诉，愿意表达本身就是很好的习惯。";
        return riskPart + emotionPart + sessionPart;
    }
}
