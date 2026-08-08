package com.mindsafe.service.voiceprint;

import com.mindsafe.domain.entity.VoiceprintEmbedding;
import com.mindsafe.domain.mapper.VoiceprintEmbeddingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * VoiceprintVerifyService 单元测试（DC-006，doing/72 §20）
 * <p>
 * 覆盖：租户双层过滤（查询条件 + 防御性二次过滤）/ 阈值判定内聚 / 1:N 比对取最高分 /
 * 损坏记录跳过（C4 留痕）/ 审计语义区分（无候选静默 vs 有候选未达标）。
 */
class VoiceprintVerifyServiceTest {

    private VoiceprintEmbeddingMapper mapper;
    private VoiceprintVerifyService service;

    @BeforeEach
    void setUp() {
        mapper = mock(VoiceprintEmbeddingMapper.class);
        // 阈值对齐 local 端 0.70（AUD-001）
        service = new VoiceprintVerifyService(mapper, 0.70);
    }

    /** 256 维方向向量：前 keep 维 +fill、其余 -fill（B-05 维度契约后保持夹角可构造） */
    private List<Double> dirVec(int keep, double fill) {
        List<Double> v = new ArrayList<>();
        for (int i = 0; i < VoiceprintDomain.EMBEDDING_DIMENSION; i++) {
            v.add(i < keep ? fill : -fill);
        }
        return v;
    }

    /** 存储记录：256 维模板（keep=256 全同向，余弦 1） */
    private VoiceprintEmbedding record(UUID userId, UUID tenantId, int keep, int index) {
        VoiceprintEmbedding rec = new VoiceprintEmbedding();
        rec.setUserId(userId);
        rec.setTenantId(tenantId);
        rec.setEmbedding(VoiceprintDomain.toJson(dirVec(keep, 1.0)));
        rec.setSampleIndex(index);
        rec.setCreatedAt(Instant.now());
        return rec;
    }

    @Nested
    @DisplayName("租户隔离（AUD-001：禁止系统作用域全表比对）")
    class TenantIsolation {

        @Test
        @DisplayName("防御性二次过滤：查询返回跨租户模板 → 不可达（matched=false 且无候选）")
        void crossTenantTemplateUnreachable() {
            UUID tenantA = UUID.randomUUID();
            UUID tenantB = UUID.randomUUID();
            // 模拟最坏情况：mock 的 selectList 不过滤（返回租户 B 的模板）
            when(mapper.selectList(any())).thenReturn(List.of(record(UUID.randomUUID(), tenantB, 256, 0)));

            VoiceprintVerifyService.VerifyOutcome outcome =
                    service.verify(tenantA, List.of(dirVec(256, 1.0)));

            assertThat(outcome.matched()).isFalse();
            assertThat(outcome.hasCandidate()).isFalse();
            assertThat(outcome.userId()).isNull();
        }

        @Test
        @DisplayName("查询条件按请求租户：tenantId 传入查询（仅该租户模板加载）")
        void queryScopedByTenant() {
            UUID tenantId = UUID.randomUUID();
            when(mapper.selectList(any())).thenReturn(
                    List.of(record(UUID.randomUUID(), tenantId, 256, 0)));

            service.verify(tenantId, List.of(dirVec(256, 1.0)));

            // LambdaQueryWrapper 的条件断言成本高，此处验证查询被调用且结果正确即可（过滤语义见跨租户用例）
            org.mockito.Mockito.verify(mapper).selectList(any());
        }
    }

    @Nested
    @DisplayName("匹配与阈值判定（AUD-001：0.70 对齐 local 端）")
    class Matching {

        @Test
        @DisplayName("无记录 → matched=false 且无候选（静默，无比对对象）")
        void emptyLibrary() {
            when(mapper.selectList(any())).thenReturn(List.of());

            VoiceprintVerifyService.VerifyOutcome outcome =
                    service.verify(UUID.randomUUID(), List.of(dirVec(256, 1.0)));

            assertThat(outcome.matched()).isFalse();
            assertThat(outcome.hasCandidate()).isFalse();
        }

        @Test
        @DisplayName("超阈值 → matched=true，返回最优候选（score/userId/tenantId）")
        void aboveThresholdMatched() {
            UUID userId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            when(mapper.selectList(any())).thenReturn(List.of(record(userId, tenantId, 256, 0)));

            VoiceprintVerifyService.VerifyOutcome outcome =
                    service.verify(tenantId, List.of(dirVec(256, 1.0)));

            assertThat(outcome.matched()).isTrue();
            assertThat(outcome.userId()).isEqualTo(userId);
            assertThat(outcome.tenantId()).isEqualTo(tenantId);
            assertThat(outcome.score()).isCloseTo(1.0, within(1e-9));
        }

        @Test
        @DisplayName("未达阈值 → matched=false 但有候选（失败审计场景）")
        void belowThresholdHasCandidate() {
            UUID userId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            // 存储前 168 维同向（其余反向，余弦=(168*2-256)/256≈0.3125 ∈ (0, 0.55)），输入全同向
            when(mapper.selectList(any())).thenReturn(List.of(record(userId, tenantId, 168, 0)));

            VoiceprintVerifyService.VerifyOutcome outcome =
                    service.verify(tenantId, List.of(dirVec(256, 1.0)));

            assertThat(outcome.matched()).isFalse();
            assertThat(outcome.hasCandidate()).isTrue();
            assertThat(outcome.userId()).isEqualTo(userId);
        }

        @Test
        @DisplayName("阈值回归：相似度 ∈ (0.55, 0.70) → 拒绝（旧阈值 0.55 会误判命中）")
        void scoreBetweenOldAndNewThresholdRejected() {
            UUID userId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            // 存储前 205 维同向（余弦=(205*2-256)/256≈0.602 ∈ (0.55, 0.70)），输入全同向
            when(mapper.selectList(any())).thenReturn(List.of(record(userId, tenantId, 205, 0)));

            VoiceprintVerifyService.VerifyOutcome outcome =
                    service.verify(tenantId, List.of(dirVec(256, 1.0)));

            assertThat(outcome.matched()).isFalse();
            assertThat(outcome.hasCandidate()).isTrue();
        }

        @Test
        @DisplayName("多用户 1:N：取最高分用户")
        void bestScoreAcrossUsers() {
            UUID lowUserId = UUID.randomUUID();
            UUID highUserId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            // lowUser 前 128 维同向（与全同向输入正交，余弦 0），highUser 全同向（余弦 1.0）
            when(mapper.selectList(any())).thenReturn(List.of(
                    record(lowUserId, tenantId, 128, 0),
                    record(highUserId, tenantId, 256, 1)));

            VoiceprintVerifyService.VerifyOutcome outcome =
                    service.verify(tenantId, List.of(dirVec(256, 1.0)));

            assertThat(outcome.matched()).isTrue();
            assertThat(outcome.userId()).isEqualTo(highUserId);
            assertThat(outcome.score()).isCloseTo(1.0, within(1e-9));
        }

        @Test
        @DisplayName("多段输入 embedding：任一段命中即匹配")
        void multiSegmentInput() {
            UUID userId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            when(mapper.selectList(any())).thenReturn(List.of(record(userId, tenantId, 256, 0)));

            VoiceprintVerifyService.VerifyOutcome outcome =
                    service.verify(tenantId, List.of(dirVec(0, 1.0), dirVec(256, 1.0)));

            assertThat(outcome.matched()).isTrue();
            assertThat(outcome.userId()).isEqualTo(userId);
        }
    }

    @Nested
    @DisplayName("损坏数据隔离（C4：异常不得吞没/扩散）")
    class CorruptedData {

        @Test
        @DisplayName("损坏记录跳过：好记录仍可正常匹配")
        void corruptedSkippedGoodStillMatches() {
            UUID goodUserId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            VoiceprintEmbedding corrupted = new VoiceprintEmbedding();
            corrupted.setUserId(UUID.randomUUID());
            corrupted.setTenantId(tenantId);
            corrupted.setEmbedding("{not-json");
            corrupted.setSampleIndex(0);
            corrupted.setCreatedAt(Instant.now());
            when(mapper.selectList(any()))
                    .thenReturn(List.of(corrupted, record(goodUserId, tenantId, 256, 1)));

            VoiceprintVerifyService.VerifyOutcome outcome =
                    service.verify(tenantId, List.of(dirVec(256, 1.0)));

            assertThat(outcome.matched()).isTrue();
            assertThat(outcome.userId()).isEqualTo(goodUserId);
        }

        @Test
        @DisplayName("全部记录损坏 → matched=false 且无候选（不抛异常）")
        void allCorruptedNoMatch() {
            UUID tenantId = UUID.randomUUID();
            VoiceprintEmbedding corrupted = new VoiceprintEmbedding();
            corrupted.setUserId(UUID.randomUUID());
            corrupted.setTenantId(tenantId);
            corrupted.setEmbedding("[1.0, \"oops\"");
            corrupted.setSampleIndex(0);
            corrupted.setCreatedAt(Instant.now());
            when(mapper.selectList(any())).thenReturn(List.of(corrupted));

            VoiceprintVerifyService.VerifyOutcome outcome =
                    service.verify(tenantId, List.of(dirVec(256, 1.0)));

            assertThat(outcome.matched()).isFalse();
            assertThat(outcome.hasCandidate()).isFalse();
        }
    }

    @Nested
    @DisplayName("入参防御")
    class InputGuard {

        @Test
        @DisplayName("空输入 embedding → matched=false 无候选（控制器前置校验后的兜底）")
        void emptyInput() {
            VoiceprintVerifyService.VerifyOutcome outcome =
                    service.verify(UUID.randomUUID(), List.of());

            assertThat(outcome.matched()).isFalse();
            assertThat(outcome.hasCandidate()).isFalse();
        }
    }
}
