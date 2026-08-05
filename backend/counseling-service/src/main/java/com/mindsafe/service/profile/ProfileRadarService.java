package com.mindsafe.service.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.domain.entity.StudentProfile;
import com.mindsafe.domain.mapper.StudentProfileMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 画像雷达图服务（PROF-004，对齐 design/23 §6）
 * <p>
 * 从 student_profiles 的 6 个 JSONB 维度字段计算 0-100 分，
 * 供教师端雷达图可视化。不暴露原始 JSON，只输出分数 + 里程碑。
 * <p>
 * 6 维度：情绪稳定度 / 沟通开放度 / 心理韧性 / 风险指数（反向）/ 社交满意度 / 成长动力
 */
@Service
public class ProfileRadarService {

    private static final Logger log = LoggerFactory.getLogger(ProfileRadarService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认中间分（数据不足时） */
    private static final int DEFAULT_SCORE = 50;

    private final StudentProfileMapper profileMapper;

    public ProfileRadarService(StudentProfileMapper profileMapper) {
        this.profileMapper = profileMapper;
    }

    /**
     * 获取学生画像雷达图数据。
     *
     * @return 包含 dimensions（6 维度分数）、milestones（里程碑列表）、totalSessions
     */
    public Map<String, Object> getRadarData(UUID tenantId, UUID studentUserId) {
        StudentProfile profile = profileMapper.selectOne(
                new LambdaQueryWrapper<StudentProfile>()
                        .eq(StudentProfile::getTenantId, tenantId)
                        .eq(StudentProfile::getUserId, studentUserId)
        );

        Map<String, Object> result = new LinkedHashMap<>();

        if (profile == null) {
            // 无画像数据，返回全维度默认分
            result.put("dimensions", defaultDimensions());
            result.put("milestones", List.of());
            result.put("totalSessions", 0);
            result.put("hasProfile", false);
            return result;
        }

        List<Map<String, Object>> dimensions = new ArrayList<>();
        dimensions.add(dim("情绪稳定度", scoreEmotionStability(profile.getEmotionBaseline())));
        dimensions.add(dim("沟通开放度", scoreCommunicationOpenness(profile.getCommunicationPref())));
        dimensions.add(dim("心理韧性", scoreResilience(profile.getResilience())));
        dimensions.add(dim("风险指数", scoreRiskInverse(profile.getRiskTrajectory())));
        dimensions.add(dim("社交满意度", scoreSocialSatisfaction(profile.getSocialGraph())));
        dimensions.add(dim("成长动力", scoreGrowthMomentum(profile.getGrowthTrack())));

        result.put("dimensions", dimensions);
        result.put("milestones", extractMilestones(profile.getGrowthTrack()));
        result.put("totalSessions", profile.getTotalSessions() != null ? profile.getTotalSessions() : 0);
        result.put("hasProfile", true);
        result.put("lastUpdatedAt", profile.getLastUpdatedAt() != null ? profile.getLastUpdatedAt().toString() : null);

        return result;
    }

    // ===== 维度评分逻辑（启发式，从 JSONB 结构提取关键指标） =====

    private int scoreEmotionStability(String json) {
        JsonNode node = parse(json);
        if (node == null) return DEFAULT_SCORE;

        // 积极情绪占比越高 + 波动度越低 → 分越高
        int score = DEFAULT_SCORE;
        if (node.has("positive_ratio")) {
            double ratio = node.get("positive_ratio").asDouble(0.5);
            score = (int) (ratio * 100);
        } else if (node.has("distribution")) {
            // 从情绪分布推算：positive 类标签占比
            JsonNode dist = node.get("distribution");
            if (dist.isObject()) {
                double total = 0, positive = 0;
                Iterator<Map.Entry<String, JsonNode>> fields = dist.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> e = fields.next();
                    double v = e.getValue().asDouble(0);
                    total += v;
                    if (e.getKey().matches("happy|calm|grateful|excited|content")) positive += v;
                }
                if (total > 0) score = (int) (positive / total * 100);
            }
        }
        // 波动度惩罚
        if (node.has("volatility")) {
            double vol = node.get("volatility").asDouble(0.5);
            score = (int) (score * (1.0 - vol * 0.3));
        }
        return clamp(score);
    }

    private int scoreCommunicationOpenness(String json) {
        JsonNode node = parse(json);
        if (node == null) return DEFAULT_SCORE;

        int score = DEFAULT_SCORE;
        // expression_depth: 写入侧为 0-1 浮点（越深越大），兼容历史字符串 shallow/moderate/deep
        if (node.has("expression_depth")) {
            JsonNode depthNode = node.get("expression_depth");
            if (depthNode.isNumber()) {
                score = 35 + (int) (Math.max(0, Math.min(1, depthNode.asDouble())) * 50);
            } else {
                String depth = depthNode.asText("moderate");
                score = switch (depth) {
                    case "deep" -> 85;
                    case "moderate" -> 60;
                    default -> 35;
                };
            }
        }
        // preferred_style 加分：expressive > narrative > reserved
        if (node.has("preferred_style")) {
            String style = node.get("preferred_style").asText("");
            if (style.contains("expressive")) score = Math.min(100, score + 10);
            else if (style.contains("reserved")) score = Math.max(0, score - 10);
        }
        return clamp(score);
    }

    private int scoreResilience(String json) {
        JsonNode node = parse(json);
        if (node == null) return DEFAULT_SCORE;

        int score = DEFAULT_SCORE;
        // self_efficacy: 0-1 浮点
        if (node.has("self_efficacy")) {
            score = (int) (node.get("self_efficacy").asDouble(0.5) * 100);
        }
        // coping_skills 使用次数越多 → 韧性越强
        if (node.has("coping_skills_used") && node.get("coping_skills_used").isArray()) {
            int skillCount = node.get("coping_skills_used").size();
            score = clamp(score + skillCount * 5);
        } else if (node.has("coping_skills")) {
            // 写入侧契约：coping_skills 为 skill → {uses, effective} 对象映射，兼容数组形态
            JsonNode skills = node.get("coping_skills");
            int totalUses = 0;
            if (skills.isArray()) {
                for (JsonNode skill : skills) {
                    totalUses += skill.has("uses") ? skill.get("uses").asInt(0) : 0;
                }
            } else if (skills.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = skills.fields();
                while (fields.hasNext()) {
                    JsonNode skill = fields.next().getValue();
                    totalUses += skill.has("uses") ? skill.get("uses").asInt(0) : 0;
                }
            }
            score = clamp(score + Math.min(totalUses * 2, 20));
        }
        return clamp(score);
    }

    private int scoreRiskInverse(String json) {
        JsonNode node = parse(json);
        if (node == null) return DEFAULT_SCORE;

        // 风险越高分越低（反向）：100 = 无风险，0 = 极高风险
        int score = 80; // 默认偏好
        if (node.has("level_distribution")) {
            JsonNode dist = node.get("level_distribution");
            if (dist.isObject()) {
                double total = 0, weighted = 0;
                Iterator<Map.Entry<String, JsonNode>> fields = dist.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> e = fields.next();
                    double count = e.getValue().asDouble(0);
                    int level = Integer.parseInt(e.getKey());
                    total += count;
                    weighted += level * count;
                }
                if (total > 0) {
                    double avgLevel = weighted / total;
                    score = (int) (100 - avgLevel / 3.0 * 100);
                }
            }
        } else if (node.has("trend")) {
            String trend = node.get("trend").asText("stable");
            score = switch (trend) {
                case "decreasing" -> 75;
                case "stable" -> 60;
                case "increasing" -> 30;
                default -> 50;
            };
        }
        return clamp(score);
    }

    private int scoreSocialSatisfaction(String json) {
        JsonNode node = parse(json);
        if (node == null) return DEFAULT_SCORE;

        int score = DEFAULT_SCORE;
        // help_seeking: 写入侧为 0-1 浮点（越主动越高），兼容历史字符串 willing/moderate/reluctant
        if (node.has("help_seeking")) {
            JsonNode hsNode = node.get("help_seeking");
            if (hsNode.isNumber()) {
                score = 30 + (int) (Math.max(0, Math.min(1, hsNode.asDouble())) * 50);
            } else {
                String hs = hsNode.asText("moderate");
                score = switch (hs) {
                    case "willing", "active" -> 80;
                    case "moderate" -> 55;
                    default -> 30;
                };
            }
        }
        // key_persons 正面情感越多 → 社交越健康（写入侧契约：role → {role, sentiment} 对象映射，兼容数组）
        if (node.has("key_persons")) {
            JsonNode persons = node.get("key_persons");
            double sentimentSum = 0;
            int count = 0;
            if (persons.isArray()) {
                for (JsonNode person : persons) {
                    if (person.has("sentiment")) {
                        sentimentSum += person.get("sentiment").asDouble(0);
                        count++;
                    }
                }
            } else if (persons.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = persons.fields();
                while (fields.hasNext()) {
                    JsonNode person = fields.next().getValue();
                    if (person.has("sentiment")) {
                        sentimentSum += person.get("sentiment").asDouble(0);
                        count++;
                    }
                }
            }
            if (count > 0) {
                double avgSentiment = sentimentSum / count; // -1 to 1
                score = clamp((int) (score + avgSentiment * 20));
            }
        }
        return clamp(score);
    }

    private int scoreGrowthMomentum(String json) {
        JsonNode node = parse(json);
        if (node == null) return DEFAULT_SCORE;

        int score = 40; // 无数据偏低（成长需积累）
        // milestones 越多 → 成长动力越强
        if (node.has("milestones") && node.get("milestones").isArray()) {
            int milestoneCount = node.get("milestones").size();
            score = clamp(40 + milestoneCount * 15);
        }
        // session_frequency 规律性加分
        if (node.has("session_frequency")) {
            JsonNode freq = node.get("session_frequency");
            if (freq.has("regularity")) {
                String reg = freq.get("regularity").asText("irregular");
                if ("regular".equals(reg)) score = clamp(score + 10);
            }
        }
        return clamp(score);
    }

    // ===== 里程碑提取 =====

    private List<Map<String, String>> extractMilestones(String growthTrackJson) {
        JsonNode node = parse(growthTrackJson);
        if (node == null || !node.has("milestones") || !node.get("milestones").isArray()) {
            return List.of();
        }
        List<Map<String, String>> milestones = new ArrayList<>();
        for (JsonNode m : node.get("milestones")) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("type", m.has("type") ? m.get("type").asText() : "unknown");
            item.put("period", m.has("period") ? m.get("period").asText() : "");
            item.put("label", milestoneLabel(m.has("type") ? m.get("type").asText() : ""));
            milestones.add(item);
        }
        return milestones;
    }

    private String milestoneLabel(String type) {
        return switch (type) {
            case "first_voluntary_share" -> "首次主动分享";
            case "first_cbt_skill_use" -> "首次使用心理技巧";
            case "consistent_attendance" -> "持续稳定参与";
            case "emotion_regulation_improved" -> "情绪调节改善";
            case "social_conflict_resolved" -> "社交冲突化解";
            case "help_seeking_initiated" -> "主动求助";
            default -> type;
        };
    }

    // ===== 工具方法 =====

    private List<Map<String, Object>> defaultDimensions() {
        List<Map<String, Object>> dims = new ArrayList<>();
        dims.add(dim("情绪稳定度", DEFAULT_SCORE));
        dims.add(dim("沟通开放度", DEFAULT_SCORE));
        dims.add(dim("心理韧性", DEFAULT_SCORE));
        dims.add(dim("风险指数", DEFAULT_SCORE));
        dims.add(dim("社交满意度", DEFAULT_SCORE));
        dims.add(dim("成长动力", DEFAULT_SCORE));
        return dims;
    }

    private Map<String, Object> dim(String name, int score) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", name);
        d.put("score", score);
        return d;
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            log.debug("画像 JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }
}
