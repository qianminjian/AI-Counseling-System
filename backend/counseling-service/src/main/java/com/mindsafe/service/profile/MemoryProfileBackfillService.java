package com.mindsafe.service.profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mindsafe.domain.entity.StudentProfile;
import com.mindsafe.domain.mapper.StudentProfileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 长期记忆→画像回注服务（MEM-101，design/50 §5.1）
 * <p>
 * 关键事件提取落库后异步回注画像（provenance=memory）：
 * <ul>
 *   <li>milestone 类事件 → growthTrack.milestones（成长节点：突破/承诺/首次尝试）</li>
 *   <li>person 类事件 → socialGraph.key_persons（按 role 代号合并，中性表述，不覆盖已有 sentiment）</li>
 * </ul>
 * riskTrajectory/emotionBaseline 的记忆关联属 MEM-103（P2），本服务不做。
 * 红线（design/50 §8.2）：只回注泛化描述，不含原始对话；失败静默降级不影响主流程。
 */
@Service
public class MemoryProfileBackfillService {

    private static final Logger log = LoggerFactory.getLogger(MemoryProfileBackfillService.class);

    /** MEM-101：记忆回注来源标识 */
    private static final String PROVENANCE_MEMORY = "memory";

    private final StudentProfileMapper profileMapper;
    // ARCH-010 P2-2：注入唯一 ObjectMapper（此前 new，配置不统一）
    private final ObjectMapper objectMapper;

    public MemoryProfileBackfillService(StudentProfileMapper profileMapper,
                                        ObjectMapper objectMapper) {
        this.profileMapper = profileMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 记忆事件（已由 LLM 泛化，不含真实姓名/地名）
     *
     * @param eventType      milestone / person / other（other 不回注）
     * @param content        泛化描述
     * @param personRole     人物 role 代号（仅 person 类有值，如 妈妈/同学/老师）
     * @param emotionContext 情绪上下文标签（可为 null）
     */
    public record MemoryEvent(String eventType, String content, String personRole, String emotionContext) {
    }

    /**
     * 回注画像（会话结束异步链路内调用，不阻塞主流程）
     */
    public void backfill(UUID tenantId, UUID userId, List<MemoryEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        try {
            StudentProfile profile = profileMapper.selectOne(
                    new LambdaQueryWrapper<StudentProfile>()
                            .eq(StudentProfile::getTenantId, tenantId)
                            .eq(StudentProfile::getUserId, userId)
            );
            if (profile == null) {
                // 画像尚未由 P0 聚合创建，跳过（下次会话结束聚合会先建画像）
                log.debug("画像不存在，跳过记忆回注: userId={}", userId);
                return;
            }

            ObjectNode growthTrack = ProfileMetaStamper.parseObject(objectMapper, profile.getGrowthTrack());
            ObjectNode socialGraph = ProfileMetaStamper.parseObject(objectMapper, profile.getSocialGraph());
            boolean changed = false;
            for (MemoryEvent event : events) {
                if (event.content() == null || event.content().isBlank()) continue;
                if ("milestone".equals(event.eventType())) {
                    changed |= appendMilestone(growthTrack, event);
                } else if ("person".equals(event.eventType())
                        && event.personRole() != null && !event.personRole().isBlank()) {
                    changed |= upsertKeyPerson(socialGraph, event);
                }
            }
            if (!changed) {
                return;
            }

            StudentProfile update = new StudentProfile();
            update.setProfileId(profile.getProfileId());
            update.setGrowthTrack(objectMapper.writeValueAsString(growthTrack));
            update.setSocialGraph(objectMapper.writeValueAsString(socialGraph));
            update.setVersion(profile.getVersion() == null ? 1 : profile.getVersion() + 1);
            update.setLastUpdatedAt(Instant.now());
            profileMapper.updateById(update);
            log.info("记忆回注画像完成: userId={}, events={}", userId, events.size());
        } catch (Exception e) {
            log.warn("记忆回注画像失败（不影响主流程）: userId={}, error={}", userId, e.getMessage());
        }
    }

    /** 里程碑事件追加进 growthTrack.milestones（按 content 去重，type=memory_key_event） */
    private boolean appendMilestone(ObjectNode growthTrack, MemoryEvent event) {
        ArrayNode milestones = growthTrack.has("milestones") && growthTrack.get("milestones").isArray()
                ? (ArrayNode) growthTrack.get("milestones")
                : growthTrack.putArray("milestones");
        for (JsonNode m : milestones) {
            if (event.content().equals(m.path("content").asText())) {
                return false; // 同内容事件不重复记
            }
        }
        ObjectNode m = milestones.addObject();
        m.put("type", "memory_key_event");
        m.put("content", event.content());
        if (event.emotionContext() != null && !event.emotionContext().isBlank()) {
            m.put("emotion_context", event.emotionContext());
        }
        m.put("detected_at", Instant.now().toString());
        ProfileMetaStamper.stamp(growthTrack, "milestones", PROVENANCE_MEMORY);
        return true;
    }

    /** 人物事件合并进 socialGraph.key_persons（role 为稳定键；不覆盖 LLM 提炼的 sentiment） */
    private boolean upsertKeyPerson(ObjectNode socialGraph, MemoryEvent event) {
        ObjectNode keyPersons = socialGraph.has("key_persons") && socialGraph.get("key_persons").isObject()
                ? (ObjectNode) socialGraph.get("key_persons")
                : socialGraph.putObject("key_persons");
        String role = event.personRole().trim();
        ObjectNode entry = keyPersons.has(role) && keyPersons.get(role).isObject()
                ? (ObjectNode) keyPersons.get(role)
                : keyPersons.putObject(role);
        entry.put("role", role);
        entry.put("mention_count", entry.path("mention_count").asInt(0) + 1);
        entry.put("last_event", event.content());
        entry.put("last_event_at", Instant.now().toString());
        ProfileMetaStamper.stamp(socialGraph, "key_persons", PROVENANCE_MEMORY);
        return true;
    }
}
