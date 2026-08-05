package com.mindsafe.service.profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mindsafe.domain.entity.StudentProfile;
import com.mindsafe.domain.mapper.StudentProfileMapper;
import com.mindsafe.service.profile.ProfileMergeGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * 画像提炼服务（PROF-003，P1：LLM 提炼）
 * <p>
 * O 专题 S1：LLM 提炼调用已上移至 MessageSummaryService 编排，本服务为纯合并职责——
 * 接收已解析的 JsonNode patch，将沟通偏好 / 心理韧性 / 社交图谱 / 性格特征 / 成长轨迹增量
 * 合并进 {@code student_profiles}。仅处理结构化标签，不接触原始对话（隐私最小化）。
 * <ul>
 *   <li>communication_pref：preferred_style / expression_depth 取最新值</li>
 *   <li>resilience：coping_skills 累加使用次数，self_efficacy 取最新值</li>
 *   <li>social_graph：key_persons 按 role 增量更新情感倾向，help_seeking 取最新值</li>
 * </ul>
 * PROF-025：每次合并同时在维度 JSONB 内的 {@code _meta} 节点记录字段级元数据
 * （provenance=llm_extract / confidence / evidence_count / updated_at / last_seen_at），
 * 供画像→编排接线做置信门控；不改表结构。
 * 失败静默降级，不影响主流程。
 */
@Service
public class ProfileExtractorService {

    private static final Logger log = LoggerFactory.getLogger(ProfileExtractorService.class);

    /** PROF-025：LLM 提炼来源标识 */
    private static final String PROVENANCE_LLM = "llm_extract";

    private final StudentProfileMapper profileMapper;
    private final ProfileMergeGate profileMergeGate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProfileExtractorService(StudentProfileMapper profileMapper,
                                   ProfileMergeGate profileMergeGate) {
        this.profileMapper = profileMapper;
        this.profileMergeGate = profileMergeGate;
    }

    /**
     * 合并画像增量（S1：LLM 调用已上移，直接接收已解析 patch）。
     *
     * @param tenantId 租户 ID
     * @param userId   学生用户 ID
     * @param patch    画像增量 JsonNode（profile_patch 节点，非对象时静默跳过）
     */
    public void extractAndMerge(UUID tenantId, UUID userId, JsonNode patch) {
        try {
            if (patch == null || !patch.isObject()) {
                return;
            }

            StudentProfile profile = profileMapper.selectOne(
                    new LambdaQueryWrapper<StudentProfile>()
                            .eq(StudentProfile::getTenantId, tenantId)
                            .eq(StudentProfile::getUserId, userId)
            );
            if (profile == null) {
                // 画像尚未由 P0 聚合创建，跳过本次 LLM 合并（下次会话结束 P0 聚合会先建画像）
                log.debug("画像不存在，跳过 LLM 提炼合并: userId={}", userId);
                return;
            }

            ObjectNode commPref = mergeCommunicationPref(parseObject(profile.getCommunicationPref()), patch.path("communication_pref"));
            ObjectNode resilience = mergeResilience(parseObject(profile.getResilience()), patch.path("resilience"));
            ObjectNode socialGraph = mergeSocialGraph(parseObject(profile.getSocialGraph()), patch.path("social_graph"));
            ObjectNode growthTrack = mergeGrowthTrack(parseObject(profile.getGrowthTrack()),
                    profile.getTotalSessions(), patch.path("resilience").path("coping_skills_used"));
            ObjectNode personalityTraits = mergePersonalityTraits(parseObject(profile.getPersonalityTraits()), patch.path("personality_traits"));

            StudentProfile update = new StudentProfile();
            update.setProfileId(profile.getProfileId());
            update.setCommunicationPref(objectMapper.writeValueAsString(commPref));
            update.setResilience(objectMapper.writeValueAsString(resilience));
            update.setSocialGraph(objectMapper.writeValueAsString(socialGraph));
            update.setGrowthTrack(objectMapper.writeValueAsString(growthTrack));
            update.setPersonalityTraits(objectMapper.writeValueAsString(personalityTraits));
            update.setVersion(profile.getVersion() == null ? 1 : profile.getVersion() + 1);
            update.setLastUpdatedAt(Instant.now());
            profileMapper.updateById(update);

            log.info("画像 LLM 提炼合并完成: userId={}", userId);
        } catch (Exception e) {
            log.warn("画像 LLM 提炼失败（不影响主流程）: userId={}, error={}", userId, e.getMessage());
        }
    }

    // ===== 维度合并 =====

    private ObjectNode mergePersonalityTraits(ObjectNode existing, JsonNode patchNode) {
        if (patchNode == null || patchNode.isMissingNode() || patchNode.isNull()) {
            return existing;
        }
        // 数值维度：指数移动平均（新值权重 0.4，历史 0.6）——避免单次会话过度影响
        mergeEmaField(existing, patchNode, "introversion", 0.4);
        mergeEmaField(existing, patchNode, "sensitivity", 0.4);
        mergeEmaField(existing, patchNode, "curiosity", 0.4);

        // dominant_interests：累加去重（最多保留 6 个）
        JsonNode interests = patchNode.path("dominant_interests");
        if (interests.isArray() && !interests.isEmpty()) {
            ArrayNode existingInterests = existing.has("dominant_interests") && existing.get("dominant_interests").isArray()
                    ? (ArrayNode) existing.get("dominant_interests")
                    : existing.putArray("dominant_interests");
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            existingInterests.forEach(n -> seen.add(n.asText()));
            for (JsonNode item : interests) {
                String tag = item.asText("").trim();
                if (!tag.isEmpty()) seen.add(tag);
            }
            existingInterests.removeAll();
            seen.stream().limit(6).forEach(existingInterests::add);
            stampMeta(existing, "dominant_interests");
        }
        return existing;
    }

    private void mergeEmaField(ObjectNode existing, JsonNode patch, String field, double alpha) {
        if (!patch.hasNonNull(field)) return;
        double newVal = patch.get(field).asDouble();
        if (existing.hasNonNull(field)) {
            double oldVal = existing.get(field).asDouble();
            // PROF-023：置信门控合并（冲突检测 + 时效衰减，防止异常单次翻转画像）
            double existingConf = readMetaConfidence(existing, field);
            ProfileMergeGate.MergeDecision decision = profileMergeGate.merge(
                    oldVal, existingConf, newVal, 0.7); // LLM 提炼默认新证据置信 0.7
            existing.put(field, Math.round(decision.mergedValue() * 100.0) / 100.0);
            if (decision.conflictDetected()) {
                log.debug("画像合并冲突检测: field={}, strategy={}, old={}, new={}",
                        field, decision.strategy(), oldVal, newVal);
            }
        } else {
            existing.put(field, Math.round(newVal * 100.0) / 100.0);
        }
        stampMeta(existing, field);
    }

    /** 从 _meta.<field>.confidence 读取字段级置信度（PROF-025），缺失默认 0.5 */
    private double readMetaConfidence(ObjectNode dimension, String field) {
        JsonNode meta = dimension.path("_meta").path(field).path("confidence");
        return meta.isNumber() ? meta.asDouble() : 0.5;
    }

    private ObjectNode mergeCommunicationPref(ObjectNode existing, JsonNode patchNode) {
        if (patchNode == null || patchNode.isMissingNode() || patchNode.isNull()) {
            return existing;
        }
        if (patchNode.hasNonNull("preferred_style")) {
            existing.put("preferred_style", patchNode.get("preferred_style").asText());
            stampMeta(existing, "preferred_style");
        }
        if (patchNode.hasNonNull("expression_depth")) {
            existing.put("expression_depth", patchNode.get("expression_depth").asDouble());
            stampMeta(existing, "expression_depth");
        }
        return existing;
    }

    private ObjectNode mergeResilience(ObjectNode existing, JsonNode patchNode) {
        if (patchNode == null || patchNode.isMissingNode() || patchNode.isNull()) {
            return existing;
        }
        // coping_skills：累加使用次数
        JsonNode skillsUsed = patchNode.path("coping_skills_used");
        if (skillsUsed.isArray() && !skillsUsed.isEmpty()) {
            ObjectNode coping = existing.has("coping_skills") && existing.get("coping_skills").isObject()
                    ? (ObjectNode) existing.get("coping_skills")
                    : existing.putObject("coping_skills");
            for (JsonNode skillNode : skillsUsed) {
                String skill = skillNode.asText();
                if (skill == null || skill.isBlank()) continue;
                ObjectNode entry = coping.has(skill) && coping.get(skill).isObject()
                        ? (ObjectNode) coping.get(skill)
                        : coping.putObject(skill);
                if (!entry.has("uses")) entry.put("uses", 0);
                if (!entry.has("effective")) entry.putNull("effective");
                entry.put("uses", entry.get("uses").asInt(0) + 1);
            }
            stampMeta(existing, "coping_skills");
        }
        if (patchNode.hasNonNull("self_efficacy")) {
            existing.put("self_efficacy", patchNode.get("self_efficacy").asDouble());
            stampMeta(existing, "self_efficacy");
        }
        return existing;
    }

    private ObjectNode mergeSocialGraph(ObjectNode existing, JsonNode patchNode) {
        if (patchNode == null || patchNode.isMissingNode() || patchNode.isNull()) {
            return existing;
        }
        // key_persons：按 role 增量更新（role 作为稳定键，便于跨会话合并）
        JsonNode persons = patchNode.path("key_persons");
        if (persons.isArray() && !persons.isEmpty()) {
            ObjectNode keyPersons = existing.has("key_persons") && existing.get("key_persons").isObject()
                    ? (ObjectNode) existing.get("key_persons")
                    : existing.putObject("key_persons");
            for (JsonNode p : persons) {
                String role = p.path("role").asText(null);
                if (role == null || role.isBlank()) continue;
                ObjectNode entry = keyPersons.has(role) && keyPersons.get(role).isObject()
                        ? (ObjectNode) keyPersons.get(role)
                        : keyPersons.putObject(role);
                entry.put("role", role);
                if (p.hasNonNull("sentiment")) {
                    entry.put("sentiment", p.get("sentiment").asDouble());
                }
            }
            stampMeta(existing, "key_persons");
        }
        if (patchNode.hasNonNull("help_seeking")) {
            existing.put("help_seeking", patchNode.get("help_seeking").asDouble());
            stampMeta(existing, "help_seeking");
        }
        return existing;
    }

    /**
     * 成长轨迹合并（PROF-004）：更新会话频率 + 规则式里程碑检测。
     */
    private ObjectNode mergeGrowthTrack(ObjectNode existing, Integer totalSessions, JsonNode copingSkillsUsed) {
        int sessions = (totalSessions != null ? totalSessions : 0) + 1;

        // session_frequency 更新
        ObjectNode freq = existing.has("session_frequency") && existing.get("session_frequency").isObject()
                ? (ObjectNode) existing.get("session_frequency")
                : existing.putObject("session_frequency");
        freq.put("total_sessions", sessions);
        freq.put("last_session_at", Instant.now().toString());
        if (sessions >= 8) freq.put("regularity", "regular");
        else if (sessions >= 3) freq.put("regularity", "moderate");
        else freq.put("regularity", "irregular");

        // 里程碑检测（规则式，事件驱动）
        ArrayNode milestones = existing.has("milestones") && existing.get("milestones").isArray()
                ? (ArrayNode) existing.get("milestones")
                : existing.putArray("milestones");

        String period = "week_" + Math.max(1, (sessions + 1) / 2);
        if (sessions == 1 && !hasMilestone(milestones, "first_session_completed")) {
            addMilestone(milestones, "first_session_completed", period);
        }
        if (sessions == 5 && !hasMilestone(milestones, "consistent_attendance")) {
            addMilestone(milestones, "consistent_attendance", period);
        }
        if (copingSkillsUsed != null && copingSkillsUsed.isArray() && !copingSkillsUsed.isEmpty()
                && !hasMilestone(milestones, "first_cbt_skill_use")) {
            addMilestone(milestones, "first_cbt_skill_use", period);
        }
        if (sessions >= 3 && !hasMilestone(milestones, "first_voluntary_share")) {
            addMilestone(milestones, "first_voluntary_share", period);
        }

        return existing;
    }

    private boolean hasMilestone(ArrayNode milestones, String type) {
        for (JsonNode m : milestones) {
            if (type.equals(m.path("type").asText())) return true;
        }
        return false;
    }

    private void addMilestone(ArrayNode milestones, String type, String period) {
        ObjectNode m = milestones.addObject();
        m.put("type", type);
        m.put("period", period);
        m.put("detected_at", Instant.now().toString());
    }

    // ===== 工具方法 =====

    /**
     * PROF-025：在维度 JSONB 的 {@code _meta.<field>} 下盖元数据戳（provenance=llm_extract）。
     */
    private void stampMeta(ObjectNode dimension, String field) {
        ProfileMetaStamper.stamp(dimension, field, PROVENANCE_LLM);
    }

    /** 解析 JSON 对象；非法/空则返回空对象节点 */
    private ObjectNode parseObject(String json) {
        return ProfileMetaStamper.parseObject(objectMapper, json);
    }

}
