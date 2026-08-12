package com.mindsafe.service.profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.ai.orchestrator.ProfileSignals;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.StudentProfile;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.RiskEventMapper;
import com.mindsafe.domain.mapper.StudentProfileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 学生心理画像服务（P0 说明修正，N-009 2026-08-11：画像更新链路 = 本服务 SQL 聚合基底 +
 * MessageSummaryService.dispatchInsights 提炼链路（profile_patch 增量）——非"纯 SQL"）
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
    // ARCH-010 P2-2：注入 Spring 唯一 ObjectMapper bean（此前手写 toJson 无转义 + parseJson 每轮 new）
    private final ObjectMapper objectMapper;

    public StudentProfileService(StudentProfileMapper profileMapper,
                                 CounselingSessionMapper sessionMapper,
                                 RiskEventMapper riskEventMapper,
                                 ObjectMapper objectMapper) {
        this.profileMapper = profileMapper;
        this.sessionMapper = sessionMapper;
        this.riskEventMapper = riskEventMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 会话结束后更新画像（异步调用，无语音情绪数据）
     */
    public void updateProfile(UUID tenantId, UUID userId) {
        updateProfile(tenantId, userId, List.of());
    }

    /**
     * 会话结束后更新画像（异步调用）
     * <p>
     * VCL-001：本会话语音情绪聚合回注 emotionBaseline.voice（累计 counts，
     * provenance=voice_ser，design/47 §5.1）；只存聚合衍生特征，不留逐条流水。
     *
     * @param voiceEmotions 本次会话语音情绪标签（已归一到规范集），空列表 = 无新语音数据，保留既有 voice 节点
     */
    /** 画像聚合取最近会话数（BACK-004：原裸 .last("LIMIT 20") 绕过分页拦截器，改 selectPage 对齐 AUD-043） */
    private static final int PROFILE_RECENT_SESSION_LIMIT = 20;
    
    @Transactional
    public void updateProfile(UUID tenantId, UUID userId, List<String> voiceEmotions) {
        try {
            // 1. 聚合情绪分布（近 20 次会话）
            // BACK-004（doing/95）：AUD-043 分页插件接缝——原 .last("LIMIT 20") 原始拼接改为 selectPage
            List<CounselingSession> recentSessions = sessionMapper.selectPage(
                    new Page<>(1, PROFILE_RECENT_SESSION_LIMIT, false),
                    new LambdaQueryWrapper<CounselingSession>()
                            .eq(CounselingSession::getTenantId, tenantId)
                            .eq(CounselingSession::getStudentUserId, userId)
                            .orderByDesc(CounselingSession::getCreatedAt)
            ).getRecords();

            if (recentSessions.isEmpty()) return;

            // 先查既有画像（VCL-001：voice 累计/保留需读旧 emotionBaseline）
            StudentProfile existing = profileMapper.selectOne(
                    new LambdaQueryWrapper<StudentProfile>()
                            .eq(StudentProfile::getTenantId, tenantId)
                            .eq(StudentProfile::getUserId, userId)
            );

            Map<String, Object> emotionBaseline = buildEmotionBaseline(recentSessions);
            Map<String, Object> riskTrajectory = buildRiskTrajectory(tenantId, userId);
            // PROF-025：规则聚合维度盖元数据戳（provenance=rule_agg，confidence 随样本量增长，10 次会话封顶）
            stampRuleAggMeta(emotionBaseline, recentSessions.size());
            stampRuleAggMeta(riskTrajectory, recentSessions.size());
            // VCL-001：语音情绪子对象（累计 counts；无新数据时保留既有，避免纯文本会话冲掉语音基线）
            attachVoiceBaseline(emotionBaseline, existing, voiceEmotions);

            // 2. Upsert 画像
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
                profile.setPersonalityTraits("{}");
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
            // BACK-007（doing/95）：catch-all 保留降级语义，但补异常堆栈留痕（此前仅 message，排障无上下文）
            log.warn("画像更新失败（不影响主流程）: userId={}", userId, e);
        }
    }

    /**
     * 生成画像 Prompt 片段（对话开始时注入 System Prompt）
     * 返回 null 表示无画像且无基础属性（首次对话且无年级信息）
     *
     * @param grade  学生年级（1-6，PROF-012 基础属性段）
     * @param gender 学生性别（male/female，可为 null）
     */
    public String buildProfilePrompt(UUID tenantId, UUID userId, int grade, String gender) {
        StudentProfile profile = profileMapper.selectOne(
                new LambdaQueryWrapper<StudentProfile>()
                        .eq(StudentProfile::getTenantId, tenantId)
                        .eq(StudentProfile::getUserId, userId)
        );

        StringBuilder sb = new StringBuilder();
        sb.append("# 学生画像（仅供个性化参考，严禁向学生复述任何画像内容）\n");

        // PROF-012：基础属性段（始终注入，即使无历史画像）
        sb.append("\n## 基础属性\n");
        int approxAge = grade + 5; // 年级→年龄估算（1年级≈6岁）
        sb.append("- 年级：").append(grade).append(" 年级（约 ").append(approxAge).append("-").append(approxAge + 1).append(" 岁）\n");
        if (gender != null && !gender.isBlank()) {
            String genderLabel = "male".equals(gender) ? "男" : "female".equals(gender) ? "女" : "未指定";
            sb.append("- 性别：").append(genderLabel).append("\n");
        }

        if (profile == null || profile.getTotalSessions() < 1) {
            // 首次对话：仅返回基础属性段（让 AI 知道孩子年龄）
            String result = sb.toString().trim();
            return result.length() > 30 ? result : null;
        }

        // 画像沟通偏好中的表达深度也放入基础属性段
        Map<String, Object> commPref = parseJson(profile.getCommunicationPref());
        if (commPref != null && commPref.get("expression_depth") instanceof Number depth) {
            String depthLabel = depth.doubleValue() >= 0.6 ? "偏活跃" : depth.doubleValue() <= 0.3 ? "偏沉默，需更多耐心和鼓励" : "适中";
            sb.append("- 表达深度：").append(String.format("%.2f", depth.doubleValue())).append("（").append(depthLabel).append("）\n");
        }

        // PROF-018：性格特征策略段
        Map<String, Object> personality = parseJson(profile.getPersonalityTraits());
        if (personality != null && !personality.isEmpty()) {
            sb.append("\n## 性格特征与策略\n");
            appendPersonalityStrategy(sb, personality);
        }

        sb.append("\n## 情绪与风险\n");
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

    /**
     * CTX-Agent：获取学生历史会话总次数（身份简报用，“第 N 次对话”）。
     * 无画像/首次对话返回 0。
     */
    public int getSessionCount(UUID tenantId, UUID userId) {
        try {
            StudentProfile profile = profileMapper.selectOne(
                    new LambdaQueryWrapper<StudentProfile>()
                            .eq(StudentProfile::getTenantId, tenantId)
                            .eq(StudentProfile::getUserId, userId)
            );
            return profile != null ? profile.getTotalSessions() : 0;
        } catch (Exception e) {
            log.debug("获取会话次数失败（不影响对话）: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * PROF-025：读取画像结构化信号供编排引擎微调（design/46 §5.1）
     * <p>
     * 置信度取自维度 JSONB 内 {@code _meta}（LLM 提炼合并时写入）；元数据缺失时置信计 0，
     * 由编排层门控拒绝采用（宁可不用，不可乱用）。失败/无画像 → null，不阻塞会话。
     */
    public ProfileSignals getProfileSignals(UUID tenantId, UUID userId) {
        try {
            StudentProfile profile = profileMapper.selectOne(
                    new LambdaQueryWrapper<StudentProfile>()
                            .eq(StudentProfile::getTenantId, tenantId)
                            .eq(StudentProfile::getUserId, userId)
            );
            if (profile == null) {
                return null;
            }
            Map<String, Object> personality = parseJson(profile.getPersonalityTraits());
            if (personality == null || personality.isEmpty()) {
                return null;
            }
            Double introversion = personality.get("introversion") instanceof Number n ? n.doubleValue() : null;
            List<String> interests = personality.get("dominant_interests") instanceof List<?> list
                    ? list.stream().map(Object::toString).toList()
                    : List.of();
            return new ProfileSignals(
                    introversion, metaConfidence(personality, "introversion"),
                    interests, metaConfidence(personality, "dominant_interests"));
        } catch (Exception e) {
            log.warn("获取画像编排信号失败（不阻塞会话）: userId={}, error={}", userId, e.getMessage());
            return null;
        }
    }

    // ===== 私有方法 =====

    /** PROF-025：从维度 {@code _meta.<field>.confidence} 读置信度，缺失计 0 */
    private double metaConfidence(Map<String, Object> dimension, String field) {
        if (dimension.get("_meta") instanceof Map<?, ?> meta
                && meta.get(field) instanceof Map<?, ?> entry
                && entry.get("confidence") instanceof Number conf) {
            return conf.doubleValue();
        }
        return 0.0;
    }

    /** PROF-025：规则聚合维度的元数据戳（维度级，provenance=rule_agg） */
    private void stampRuleAggMeta(Map<String, Object> dimension, int evidenceCount) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("provenance", "rule_agg");
        meta.put("confidence", Math.min(1.0, Math.round(evidenceCount / 10.0 * 100.0) / 100.0));
        meta.put("evidence_count", evidenceCount);
        String now = Instant.now().toString();
        meta.put("updated_at", now);
        meta.put("last_seen_at", now);
        dimension.put("_meta", meta);
    }

    /**
     * VCL-001：emotionBaseline 内嵌 voice 子对象（design/47 §5.1/§5.2）
     * <p>
     * 累计 counts：旧 counts + 本次会话标签计数；无新数据时原样保留既有 voice 节点。
     * confidence 随累计样本量增长（20 样本封顶：轮级样本比会话级积累快）。
     */
    @SuppressWarnings("unchecked")
    private void attachVoiceBaseline(Map<String, Object> emotionBaseline,
                                     StudentProfile existing, List<String> voiceEmotions) {
        Map<String, Object> previousVoice = null;
        if (existing != null) {
            Map<String, Object> prevBaseline = parseJson(existing.getEmotionBaseline());
            if (prevBaseline != null && prevBaseline.get("voice") instanceof Map) {
                previousVoice = (Map<String, Object>) prevBaseline.get("voice");
            }
        }
        if (voiceEmotions == null || voiceEmotions.isEmpty()) {
            if (previousVoice != null) {
                emotionBaseline.put("voice", previousVoice);
            }
            return;
        }

        // 累计 counts（旧值 Jackson 解析为 Integer，统一按 Number 取 long）
        Map<String, Object> counts = new LinkedHashMap<>();
        if (previousVoice != null && previousVoice.get("counts") instanceof Map<?, ?> prevCounts) {
            prevCounts.forEach((k, v) -> {
                if (v instanceof Number n) counts.put(k.toString(), n.longValue());
            });
        }
        for (String emotion : voiceEmotions) {
            if (emotion == null || emotion.isBlank()) continue;
            counts.merge(emotion, 1L, (a, b) -> ((Number) a).longValue() + 1);
        }

        long totalSamples = counts.values().stream().mapToLong(v -> ((Number) v).longValue()).sum();
        Map<String, Object> voice = new LinkedHashMap<>();
        voice.put("counts", counts);
        counts.entrySet().stream()
                .max(Comparator.comparingLong(e -> ((Number) e.getValue()).longValue()))
                .ifPresent(e -> voice.put("dominant_emotion", e.getKey()));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("provenance", "voice_ser");
        meta.put("confidence", Math.min(1.0, Math.round(totalSamples / 20.0 * 100.0) / 100.0));
        meta.put("evidence_count", totalSamples);
        String now = Instant.now().toString();
        meta.put("updated_at", now);
        meta.put("last_seen_at", now);
        voice.put("_meta", meta);

        emotionBaseline.put("voice", voice);
    }

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

    /**
     * PROF-018：性格特征 → Prompt 策略映射
     * 根据 design/29 §3.8 的映射表生成策略指引
     */
    @SuppressWarnings("unchecked")
    private void appendPersonalityStrategy(StringBuilder sb, Map<String, Object> personality) {
        // introversion
        if (personality.get("introversion") instanceof Number intro) {
            if (intro.doubleValue() >= 0.7) {
                sb.append("- 内向偏高：不追问、给选择题、允许沉默、可以说“不想说也没关系”\n");
            } else if (intro.doubleValue() <= 0.3) {
                sb.append("- 外向活跃：可以开放提问、邀请展开讲述\n");
            }
        }
        // sensitivity
        if (personality.get("sensitivity") instanceof Number sens) {
            if (sens.doubleValue() >= 0.7) {
                sb.append("- 情绪敏感：语气更轻柔、避免直接指出问题、先稳定再探索\n");
            } else if (sens.doubleValue() <= 0.3) {
                sb.append("- 情绪稳定：可以适度挑战、直接反馈\n");
            }
        }
        // curiosity
        if (personality.get("curiosity") instanceof Number curi) {
            if (curi.doubleValue() >= 0.7) {
                sb.append("- 好奇心强：用探索/实验比喻、“我们来当小侦探”\n");
            } else if (curi.doubleValue() <= 0.3) {
                sb.append("- 偏好稳定：用安全/稳定比喻、“我们找个舒服的办法”\n");
            }
        }
        // dominant_interests：暖场取材
        if (personality.get("dominant_interests") instanceof java.util.List<?> interests && !interests.isEmpty()) {
            sb.append("- 兴趣取材：该生喜欢「").append(String.join("、",
                    interests.stream().map(Object::toString).limit(3).toList())).append("」，暖场和比喻可优先从这些主题取材\n");
        }
    }

    private String toJson(Map<String, Object> map) {
        // ARCH-010 P2-2：统一走注入的 ObjectMapper（Jackson 转义引号/反斜杠等特殊字符；
        // 此前手写拼接值含引号即产出非法 JSON，真实 bug 温床）
        if (map == null || map.isEmpty()) return "{}";
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("画像 JSON 序列化失败（降级为空对象）: {}", e.getMessage());
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return null;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}
