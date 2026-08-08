package com.mindsafe.service.voiceprint;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.VoiceprintEmbedding;
import com.mindsafe.domain.mapper.VoiceprintEmbeddingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 声纹录入域服务（DC-006，doing/72 §20）
 * <p>
 * 从 VoiceprintController 下沉的录入逻辑：删旧模板（按 userId）→ 写入新 embedding（限
 * maxTemplates，构造器注入）→ 返回实际写入数。
 */
@Service
public class VoiceprintEnrollService {

    private static final Logger log = LoggerFactory.getLogger(VoiceprintEnrollService.class);

    private final VoiceprintEmbeddingMapper mapper;
    private final int maxTemplates;

    public VoiceprintEnrollService(VoiceprintEmbeddingMapper mapper,
                                   @Value("${mindsafe.voiceprint.max-templates:8}") int maxTemplates) {
        this.mapper = mapper;
        this.maxTemplates = maxTemplates;
    }

    /**
     * 重新录入声纹模板（删旧写新）。
     *
     * @param userId     用户（删除范围：该用户全部旧模板）
     * @param tenantId   租户（写入归属）
     * @param embeddings 新 embedding 段（超过 maxTemplates 截断）
     * @return 实际写入条数
     */
    public int enroll(UUID userId, UUID tenantId, List<List<Double>> embeddings) {
        // 删除旧模板（重新录入）
        mapper.delete(new LambdaQueryWrapper<VoiceprintEmbedding>()
                .eq(VoiceprintEmbedding::getUserId, userId));

        if (embeddings == null || embeddings.isEmpty()) {
            return 0;
        }

        // 写入新模板（B-05：无效段过滤 + 限制最大数量）
        // 零向量/维度不符的退化段直接跳过（log.warn 留痕），有效段按序写入
        int written = 0;
        for (List<Double> emb : embeddings) {
            if (!VoiceprintDomain.isValidEmbedding(emb)) {
                log.warn("声纹模板段无效已跳过: 维度={}, 范数={}",
                        emb == null ? 0 : emb.size(), VoiceprintDomain.norm(emb));
                continue;
            }
            if (written >= maxTemplates) {
                break;
            }
            VoiceprintEmbedding entity = new VoiceprintEmbedding();
            entity.setUserId(userId);
            entity.setTenantId(tenantId);
            entity.setEmbedding(VoiceprintDomain.toJson(emb));
            entity.setSampleIndex(written);
            entity.setCreatedAt(Instant.now());
            mapper.insert(entity);
            written++;
        }
        return written;
    }
}
