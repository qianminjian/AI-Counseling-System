package com.mindsafe.service.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.ai.chat.AiChatService;
import com.mindsafe.domain.entity.LongTermMemory;
import com.mindsafe.domain.mapper.LongTermMemoryMapper;
import com.mindsafe.service.profile.MemoryProfileBackfillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 长期记忆服务（AI-008）
 * <p>
 * 职责：
 * 1. 会话结束后异步提取关键事件（LLM 提炼）
 * 2. 新会话开始时召回 top-N 记忆供 Prompt 回注
 * 3. 记忆去重（同一会话不重复提取）
 * 4. MEM-101：关键事件回注画像 growthTrack/socialGraph（provenance=memory，design/50 §5.1）
 */
@Service
public class LongTermMemoryService {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryService.class);

    /** 每次 Prompt 回注的最大记忆条数 */
    private static final int RECALL_LIMIT = 5;

    /** 记忆最大保留条数（超出时淘汰低重要性旧记忆） */
    private static final int MAX_MEMORIES_PER_STUDENT = 50;

    private final LongTermMemoryMapper memoryMapper;
    private final AiChatService aiChatService;
    private final ObjectMapper objectMapper;
    private final MemoryProfileBackfillService backfillService;

    public LongTermMemoryService(LongTermMemoryMapper memoryMapper,
                                 AiChatService aiChatService,
                                 ObjectMapper objectMapper,
                                 MemoryProfileBackfillService backfillService) {
        this.memoryMapper = memoryMapper;
        this.aiChatService = aiChatService;
        this.objectMapper = objectMapper;
        this.backfillService = backfillService;
    }

    /**
     * 异步提取关键事件（会话结束后调用，不阻塞主流程）
     *
     * @param conversationText 对话文本
     * @param sessionSummary   结构化摘要（可为 null）
     */
    @Async
    public void extractAndStoreKeyEvents(UUID tenantId, UUID studentUserId, UUID sessionId,
                                         String conversationText, String sessionSummary) {
        try {
            // 幂等：同一会话不重复提取
            Long existing = memoryMapper.selectCount(
                    new LambdaQueryWrapper<LongTermMemory>()
                            .eq(LongTermMemory::getSessionId, sessionId)
            );
            if (existing != null && existing > 0) {
                log.debug("会话已提取过关键事件，跳过: sessionId={}", sessionId);
                return;
            }

            // LLM 提取关键事件
            String rawJson = aiChatService.extractKeyEvents(conversationText, sessionSummary);
            if (rawJson == null || rawJson.isBlank()) return;

            // 解析 JSON 数组
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode events = root.isArray() ? root : root.get("key_events");
            if (events == null || !events.isArray() || events.isEmpty()) return;

            int stored = 0;
            List<MemoryProfileBackfillService.MemoryEvent> backfillEvents = new ArrayList<>();
            for (JsonNode event : events) {
                String content = getTextSafe(event, "content");
                if (content == null || content.isBlank()) continue;

                String emotion = getTextSafe(event, "emotion_context");
                float importance = 0.5f;
                if (event.has("importance") && event.get("importance").isNumber()) {
                    importance = (float) event.get("importance").asDouble(0.5);
                    importance = Math.max(0f, Math.min(1f, importance));
                }

                LongTermMemory memory = LongTermMemory.keyEvent(
                        tenantId, studentUserId, sessionId, content, emotion, importance);
                memoryMapper.insert(memory);
                stored++;

                // MEM-101：收集可回注事件（milestone/person 分类由 LLM 提取时给出）
                backfillEvents.add(new MemoryProfileBackfillService.MemoryEvent(
                        getTextSafe(event, "event_type"), content, getTextSafe(event, "person_role"), emotion));
            }

            if (stored > 0) {
                log.info("关键事件提取完成: sessionId={}, stored={}", sessionId, stored);
                evictOldMemories(tenantId, studentUserId);
                // MEM-101：记忆→画像回注（已在异步链路内，失败静默降级）
                backfillService.backfill(tenantId, studentUserId, backfillEvents);
            }
        } catch (Exception e) {
            log.warn("关键事件提取失败（不影响业务）: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    /**
     * 召回长期记忆，生成 Prompt 片段（对话开始时注入）
     *
     * @return 记忆 Prompt 片段，无记忆时返回 null
     */
    public String buildMemoryPrompt(UUID tenantId, UUID studentUserId) {
        try {
            List<LongTermMemory> memories = memoryMapper.selectList(
                    new LambdaQueryWrapper<LongTermMemory>()
                            .eq(LongTermMemory::getTenantId, tenantId)
                            .eq(LongTermMemory::getStudentUserId, studentUserId)
                            .orderByDesc(LongTermMemory::getImportance)
                            .orderByDesc(LongTermMemory::getCreatedAt)
                            .last("LIMIT " + RECALL_LIMIT)
            );

            if (memories == null || memories.isEmpty()) return null;

            StringBuilder sb = new StringBuilder();
            sb.append("## 历史记忆（跨会话关键事件，仅供个性化参考）\n");
            sb.append("以下是该学生过去对话中的重要事件，可在适当时机自然引用（如'上次你提到...'），但不要生硬复述：\n");

            for (LongTermMemory m : memories) {
                sb.append("- ");
                if (m.getEmotionContext() != null && !m.getEmotionContext().isBlank()) {
                    sb.append("[").append(m.getEmotionContext()).append("] ");
                }
                sb.append(m.getContent()).append("\n");
            }

            // 更新召回计数（fire-and-forget，不影响主流程）
            updateRecallCount(memories);

            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("长期记忆召回失败（不影响对话）: userId={}, error={}", studentUserId, e.getMessage());
            return null;
        }
    }

    /**
     * 淘汰低重要性旧记忆（保留 MAX_MEMORIES_PER_STUDENT 条）
     */
    private void evictOldMemories(UUID tenantId, UUID studentUserId) {
        Long total = memoryMapper.selectCount(
                new LambdaQueryWrapper<LongTermMemory>()
                        .eq(LongTermMemory::getTenantId, tenantId)
                        .eq(LongTermMemory::getStudentUserId, studentUserId)
        );
        if (total == null || total <= MAX_MEMORIES_PER_STUDENT) return;

        // 删除重要性最低 + 最旧的溢出部分
        long excess = total - MAX_MEMORIES_PER_STUDENT;
        List<LongTermMemory> toDelete = memoryMapper.selectList(
                new LambdaQueryWrapper<LongTermMemory>()
                        .eq(LongTermMemory::getTenantId, tenantId)
                        .eq(LongTermMemory::getStudentUserId, studentUserId)
                        .orderByAsc(LongTermMemory::getImportance)
                        .orderByAsc(LongTermMemory::getCreatedAt)
                        .last("LIMIT " + excess)
        );
        for (LongTermMemory m : toDelete) {
            memoryMapper.deleteById(m.getMemoryId());
        }
        log.debug("淘汰旧记忆: userId={}, deleted={}", studentUserId, toDelete.size());
    }

    private void updateRecallCount(List<LongTermMemory> memories) {
        try {
            for (LongTermMemory m : memories) {
                m.setRecallCount(m.getRecallCount() + 1);
                m.setLastRecalledAt(Instant.now());
                memoryMapper.updateById(m);
            }
        } catch (Exception e) {
            log.debug("更新召回计数失败（忽略）: {}", e.getMessage());
        }
    }

    private String getTextSafe(JsonNode node, String field) {
        if (node.has(field) && node.get(field).isTextual()) {
            return node.get(field).asText();
        }
        return null;
    }
}
