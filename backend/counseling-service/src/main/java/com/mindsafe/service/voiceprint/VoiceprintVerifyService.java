package com.mindsafe.service.voiceprint;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.VoiceprintEmbedding;
import com.mindsafe.domain.mapper.VoiceprintEmbeddingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 声纹验证域服务（DC-006，doing/72 §20）
 * <p>
 * 从 VoiceprintController 下沉的 1:N 比对域逻辑：
 * <ul>
 *   <li>AUD-001：查询仅限请求租户 + 防御性二次过滤——跨租户模板在任何路径下都不可达</li>
 *   <li>阈值判定内聚域内（构造器注入，与 local 端 0.70 对齐）</li>
 *   <li>C4：损坏记录跳过留痕（userId/sampleIndex 可定位），流程继续</li>
 *   <li>审计语义：{@link VerifyOutcome#hasCandidate()} 区分"无记录静默"与"有候选未达标"</li>
 * </ul>
 */
@Service
public class VoiceprintVerifyService {

    private static final Logger log = LoggerFactory.getLogger(VoiceprintVerifyService.class);

    private final VoiceprintEmbeddingMapper mapper;
    private final double threshold;

    public VoiceprintVerifyService(VoiceprintEmbeddingMapper mapper,
                                   @Value("${mindsafe.voiceprint.verify-threshold:0.70}") double threshold) {
        this.mapper = mapper;
        this.threshold = threshold;
    }

    /** 验证结果：matched=是否达阈值；hasCandidate=是否存在比对对象（失败审计区分） */
    public record VerifyOutcome(boolean matched, double score, UUID userId, UUID tenantId) {
        public boolean hasCandidate() {
            return userId != null;
        }
    }

    /**
     * 租户内 1:N 声纹比对。
     *
     * @param tenantId        请求租户（比对范围，禁止系统作用域全表比对）
     * @param inputEmbeddings 输入 embedding 段（前端录音提取）
     * @return 验证结果（无候选 → hasCandidate=false；未达阈值 → 有候选但 matched=false）
     */
    public VerifyOutcome verify(UUID tenantId, List<List<Double>> inputEmbeddings) {
        if (inputEmbeddings == null || inputEmbeddings.isEmpty()) {
            return new VerifyOutcome(false, 0, null, null);
        }

        // AUD-001：查询仅限请求租户的声纹模板（禁止系统作用域全表加载后 1:N 比对）
        // 复制为可变列表以支持防御性过滤（selectList 可能返回不可变集合）
        List<VoiceprintEmbedding> tenantRecords = new ArrayList<>(mapper.selectList(
                new LambdaQueryWrapper<VoiceprintEmbedding>()
                        .eq(VoiceprintEmbedding::getTenantId, tenantId)));

        // AUD-001 双层防护：即使查询层条件失效（未来重构/换 ORM 等），
        // 比对前仍按请求租户过滤——跨租户模板在任何路径下都不可达
        tenantRecords.removeIf(rec -> !tenantId.equals(rec.getTenantId()));

        if (tenantRecords.isEmpty()) {
            // 不回显相似度分数，防止阈值探测（SEC-007）
            return new VerifyOutcome(false, 0, null, null);
        }

        // 按 userId 分组（租户内）
        Map<UUID, List<VoiceprintEmbedding>> byUser = new LinkedHashMap<>();
        for (VoiceprintEmbedding rec : tenantRecords) {
            byUser.computeIfAbsent(rec.getUserId(), k -> new ArrayList<>()).add(rec);
        }

        log.info("[声纹验证] 输入 {} 段 embedding, 租户 {} 内 {} 条记录, {} 个用户, 阈值={}",
                inputEmbeddings.size(), tenantId, tenantRecords.size(), byUser.size(), threshold);

        double bestScore = 0;
        UUID bestUserId = null;
        UUID bestTenantId = null;

        for (Map.Entry<UUID, List<VoiceprintEmbedding>> entry : byUser.entrySet()) {
            for (List<Double> inputEmb : inputEmbeddings) {
                for (VoiceprintEmbedding stored : entry.getValue()) {
                    List<Double> storedEmb = VoiceprintDomain.parseEmbedding(stored.getEmbedding());
                    if (storedEmb == null) {
                        // C4：损坏记录不静默吞没——留痕（userId/sampleIndex 可定位），流程继续比对其他记录
                        log.warn("声纹 embedding 记录损坏已跳过: userId={}, sampleIndex={}",
                                stored.getUserId(), stored.getSampleIndex());
                        continue;
                    }
                    double score = VoiceprintDomain.cosineSimilarity(inputEmb, storedEmb);
                    if (score > bestScore) {
                        bestScore = score;
                        bestUserId = entry.getKey();
                        bestTenantId = entry.getValue().get(0).getTenantId();
                    }
                }
            }
        }

        log.info("[声纹验证] bestScore={}, bestUserId={}, matched={}",
                String.format("%.4f", bestScore), bestUserId, bestScore >= threshold);

        return new VerifyOutcome(bestScore >= threshold, bestScore, bestUserId, bestTenantId);
    }
}
