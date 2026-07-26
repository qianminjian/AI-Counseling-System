package com.mindsafe.service.profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.StudentProfile;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.mapper.StudentProfileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 学生心理画像服务（P0：纯 SQL 聚合，无额外 LLM 调用）
 * 
 * 职责：
 * 1. 会话结束后聚合统计，更新画像
 * 2. 对话开始前生成画像 Prompt 片段
 */
@Service
public class StudentProfileService {

    private static final Logger log = LoggerFactory.getLogger(StudentProfileService.class);

    private final StudentProfileMapper profileMapper;
    private final CounselingSessionMapper sessionMapper;
    private final RiskEventMapper riskEventMapper;

    public StudentProfileService(StudentProfileMapper profileMapper,
                                 CounselingSessionMapper sessionMapper,
                                 RiskEventMapper riskEventMapper) {
        this.profileMapper = profileMapper;
        this.sessionMapper = sessionMapper;
        this.riskEventMapper = riskEventMapper;
    }

    /**
     * 会话结束后更新画像（异步调用）
     */
    public void updateProfile(UUID tenantId, UUID userId) {
        try {
            // 1. 聚合情绪分布（近 20 次会话）
            List<CounselingSession> recentSessions = sessionMapper.selectList(
                    new LambdaQueryWrapper<CounselingSession>()
                            .eq(CounselingSession::getTenantId, tenantId)
                            .eq(CounselingSession::getStudentUserId, userId)
                            .orderByDesc(CounselingSession::getCreatedAt)
                            .last("LIMIT 20")
            );

            if (recentSessions.isEmpty()) return;

            Map<String, Object> emotionBaseline = buildEmotionBaseline(recentSessions);
            Map<String, Object> riskTrajectory = buildRiskTrajectory(tenantId, userId);

            // 2. Upsert 画像
            StudentProfile existing = profileMapper.selectOne(
                    new LambdaQueryWrapper<StudentProfile>()
                            .eq(StudentProfile::getTenantId, tenantId)
                            .eq(StudentProfile::getUserId, userId)
            );

            if (existing == null) {
                StudentProfile profile = new StudentProfile();
                profile.setProfileId(UUID.randomUUID());
                profile.setTenantId(tenantId);
                profile.setUserId(userId);
                profile.setEmotionBaseline(toJson(emotionBaseline));
                profile.setRiskTrajectory(toJson(riskTrajectory));
                profile.setCommunicationPref("{}");
                profile.setResilience("{}");
                profile.setSocialGraph("{}");
                profile.setGrowthTrack("{}");
                profile.setVersion(1);
                profile.setTotalSessions(recentSessions.size());
                profile.setLastUpdatedAt(Instant.now());
                profile.setCreatedAt(Instant.now());
                profileMapper.insert(profile);
            } else {
                existing.setEmotionBaseline(toJson(emotionBaseline));
                existing.setRiskTrajectory(toJson(riskTrajectory));
                existing.setTotalSessions(recentSessions.size());
                existing.setVersion(existing.getVersion() + 1);
                existing.setLastUpdatedAt(Instant.now());
                profileMapper.updateById(existing);
            }

            log.debug("画像更新完成: userId={}, sessions={}", userId, recentSessions.size());
        } catch (Exception e) {
            log.warn("画像更新失败（不影响主流程）: userId={}, error={}", userId, e.getMessage());
        }
    }

    /**
     * 生成画像 Prompt 片段（对话开始时注入 System Prompt）
     * 返回 null 表示无画像（首次对话）
     */
    public String buildProfilePrompt(UUID tenantId, UUID userId) {
        StudentProfile profile = profileMapper.selectOne(
                new LambdaQueryWrapper<StudentProfile>()
                        .eq(StudentProfile::getTenantId, tenantId)
                        .eq(StudentProfile::getUserId, userId)
        );

        if (profile == null || profile.getTotalSessions() < 1) {
            return null; // 首次对话，不注入
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# 学生画像（仅供个性化参考，严禁向学生复述任何画像内容）\n");

        // 情绪基线
        Map<String, Object> emotion = parseJson(profile.getEmotionBaseline());
        if (emotion != null && !emotion.isEmpty()) {
            String dominant = (String) emotion.get("dominant_emotion");
            Object dist = emotion.get("distribution");
            if (dominant != null) {
                sb.append("- 情绪基线：主导情绪为「").append(dominant).append("」");
                if (dist != null) sb.append("，分布：").append(dist);
                sb.append("\n");
            }
            Object topics = emotion.get("trigger_topics");
            if (topics != null && !topics.toString().equals("{}")) {
                sb.append("- 高频话题：").append(topics).append("\n");
            }
        }

        // 风险轨迹
        Map<String, Object> risk = parseJson(profile.getRiskTrajectory());
        if (risk != null && !risk.isEmpty()) {
            Object trend = risk.get("trend_30d");
            Object hasRisk = risk.get("has_recent_risk");
            if (trend != null) {
                sb.append("- 风险趋势（近30天）：").append(trend).append("\n");
            }
            if (Boolean.TRUE.equals(hasRisk)) {
                sb.append("- ⚠️ 近期有风险事件，请格外关注情绪变化\n");
            }
        }

        // 沟通偏好（PROF-003 LLM 提炼）
        Map<String, Object> commPref = parseJson(profile.getCommunicationPref());
        if (commPref != null && !commPref.isEmpty()) {
            Object style = commPref.get("preferred_style");
            if (style != null) {
                sb.append("- 沟通偏好：该生更适应「").append(style).append("」的辅导风格\n");
            }
        }

        // 已掌握技巧（PROF-003：coping_skills 使用次数）
        Map<String, Object> resilience = parseJson(profile.getResilience());
        if (resilience != null && !resilience.isEmpty()) {
            Object coping = resilience.get("coping_skills");
            if (coping instanceof Map && !((Map<?, ?>) coping).isEmpty()) {
                sb.append("- 已掌握技巧：").append(summarizeCopingSkills((Map<?, ?>) coping)).append("\n");
            }
        }

        // 会话经验
        sb.append("- 历史会话次数：").append(profile.getTotalSessions()).append("\n");

        String result = sb.toString().trim();
        return result.length() > 50 ? result : null; // 内容太少则不注入
    }

    /**
     * 获取画像沟通偏好 expression_depth（design/28 §三 3.2 冷场决策模型信号 F）
     * <p>
     * 话多（≥0.6）→ 冷场时偏留白；沉默性格（≤0.4）→ 偏主动暖场。
     *
     * @return expression_depth（0.0~1.0）；无画像/首次对话/字段缺失时返回 null（决策模型计 0，不阻塞）
     */
    public Double getExpressionDepth(UUID tenantId, UUID userId) {
        try {
            StudentProfile profile = profileMapper.selectOne(
                    new LambdaQueryWrapper<StudentProfile>()
                            .eq(StudentProfile::getTenantId, tenantId)
                            .eq(StudentProfile::getUserId, userId)
            );
            if (profile == null) {
                return null;
            }
            Map<String, Object> commPref = parseJson(profile.getCommunicationPref());
            if (commPref == null) {
                return null;
            }
            Object depth = commPref.get("expression_depth");
            if (depth instanceof Number num) {
                return num.doubleValue();
            }
            return null;
        } catch (Exception e) {
            log.warn("获取画像 expression_depth 失败（不阻塞会话）: userId={}, error={}", userId, e.getMessage());
            return null;
        }
    }

    // ===== 私有方法 =====

    private Map<String, Object> buildEmotionBaseline(List<CounselingSession> sessions) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 情绪分布
        Map<String, Long> dist = sessions.stream()
                .map(CounselingSession::getEmotionTag)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(e -> e, Collectors.counting()));

        long total = dist.values().stream().mapToLong(Long::longValue).sum();
        if (total > 0) {
            Map<String, Double> normalized = new LinkedHashMap<>();
            dist.forEach((k, v) -> normalized.put(k, Math.round(v * 100.0 / total) / 100.0));
            result.put("distribution", normalized);

            // 主导情绪
            String dominant = dist.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("neutral");
            result.put("dominant_emotion", dominant);
        }

        return result;
    }

    private Map<String, Object> buildRiskTrajectory(UUID tenantId, UUID userId) {
        Map<String, Object> result = new LinkedHashMap<>();

        Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        List<RiskEvent> recentRisks = riskEventMapper.selectList(
                new LambdaQueryWrapper<RiskEvent>()
                        .eq(RiskEvent::getTenantId, tenantId)
                        .eq(RiskEvent::getStudentUserId, userId)
                        .ge(RiskEvent::getDetectedAt, thirtyDaysAgo)
        );

        result.put("has_recent_risk", !recentRisks.isEmpty());
        result.put("risk_count_30d", recentRisks.size());

        if (!recentRisks.isEmpty()) {
            // 最高风险等级
            int maxLevel = recentRisks.stream()
                    .mapToInt(RiskEvent::getRiskLevel)
                    .max().orElse(0);
            result.put("max_level_30d", maxLevel);

            // 趋势判断（简化：对比前15天 vs 后15天）
            Instant fifteenDaysAgo = Instant.now().minus(15, ChronoUnit.DAYS);
            long olderCount = recentRisks.stream()
                    .filter(r -> r.getDetectedAt().isBefore(fifteenDaysAgo))
                    .count();
            long newerCount = recentRisks.size() - olderCount;

            String trend = newerCount > olderCount ? "rising" :
                           newerCount < olderCount ? "declining" : "stable";
            result.put("trend_30d", trend);
        } else {
            result.put("trend_30d", "stable");
        }

        return result;
    }

    /** 将 coping_skills 映射汇总为可读摘要（如：deep_breathing×3, drawing×1） */
    private String summarizeCopingSkills(Map<?, ?> coping) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<?, ?> entry : coping.entrySet()) {
            if (!first) sb.append("、");
            sb.append(entry.getKey());
            if (entry.getValue() instanceof Map) {
                Object uses = ((Map<?, ?>) entry.getValue()).get("uses");
                if (uses != null) sb.append("×").append(uses);
            }
            first = false;
        }
        return sb.toString();
    }

    private String toJson(Map<String, Object> map) {
        // 简单 JSON 序列化（避免引入额外依赖，P0 阶段够用）
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":");
            Object val = entry.getValue();
            if (val instanceof String) {
                sb.append("\"").append(val).append("\"");
            } else if (val instanceof Map) {
                sb.append(toJson((Map<String, Object>) val));
            } else {
                sb.append(val);
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return null;
        try {
            // 使用 Jackson（Spring Boot 已包含）
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            return om.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}
