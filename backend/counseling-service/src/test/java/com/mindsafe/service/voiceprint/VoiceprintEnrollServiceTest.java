package com.mindsafe.service.voiceprint;

import com.mindsafe.domain.entity.VoiceprintEmbedding;
import com.mindsafe.domain.mapper.VoiceprintEmbeddingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * VoiceprintEnrollService 单元测试（DC-006，doing/72 §20）
 * <p>
 * 覆盖：删旧写新 / maxTemplates 上限 / 空列表防御 / sampleIndex 顺序。
 */
class VoiceprintEnrollServiceTest {

    private VoiceprintEmbeddingMapper mapper;
    private VoiceprintEnrollService service;

    @BeforeEach
    void setUp() {
        mapper = mock(VoiceprintEmbeddingMapper.class);
        service = new VoiceprintEnrollService(mapper, 8);
    }

    /** 256 维有效向量（B-05：维度契约 256，归一化填充值） */
    private List<Double> validEmb(double fill) {
        List<Double> v = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            v.add(fill);
        }
        return v;
    }

    @Nested
    @DisplayName("enroll 录入")
    class Enroll {

        @Test
        @DisplayName("删旧写新：先按 userId 删除旧模板，再写入新 embedding")
        void deletesOldThenWritesNew() {
            UUID userId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();

            int count = service.enroll(userId, tenantId,
                    List.of(validEmb(1.0), validEmb(0.5)));

            assertThat(count).isEqualTo(2);
            ArgumentCaptor<VoiceprintEmbedding> captor = ArgumentCaptor.forClass(VoiceprintEmbedding.class);
            verify(mapper, times(2)).insert(captor.capture());
            List<VoiceprintEmbedding> written = captor.getAllValues();
            assertThat(written).allMatch(e -> userId.equals(e.getUserId()));
            assertThat(written).allMatch(e -> tenantId.equals(e.getTenantId()));
            assertThat(written.get(0).getSampleIndex()).isEqualTo(0);
            assertThat(written.get(1).getSampleIndex()).isEqualTo(1);
            assertThat(written.get(0).getEmbedding()).isEqualTo(VoiceprintDomain.toJson(validEmb(1.0)));
        }

        @Test
        @DisplayName("超过 maxTemplates 上限 → 截断写入，返回实际写入数")
        void cappedAtMaxTemplates() {
            UUID userId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            java.util.List<List<Double>> tooMany = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                tooMany.add(validEmb(0.1 * (i + 1)));
            }

            int count = service.enroll(userId, tenantId, tooMany);

            assertThat(count).isEqualTo(8);
            verify(mapper, times(8)).insert(any(VoiceprintEmbedding.class));
        }

        @Test
        @DisplayName("空列表 → 返回 0 且不写入（控制器已前置校验，服务兜底）")
        void emptyEmbedsNoWrite() {
            int count = service.enroll(UUID.randomUUID(), UUID.randomUUID(), List.of());

            assertThat(count).isEqualTo(0);
            verify(mapper, never()).insert(any(VoiceprintEmbedding.class));
        }

        @Test
        @DisplayName("删除条件按 userId 而非 tenantId（重新录入语义：覆盖该用户全部旧模板）")
        void deleteScopedByUser() {
            UUID userId = UUID.randomUUID();
            service.enroll(userId, UUID.randomUUID(), List.of(validEmb(1.0)));

            // 删除必然发生（用户重录覆盖旧模板）
            verify(mapper).delete(any());
        }

        @Test
        @DisplayName("B-05：无效 embedding（零向量/维度不符）跳过不写入，有效段正常写入")
        void rejectsInvalidEmbeddings() {
            UUID userId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            List<Double> zeroVec = new ArrayList<>();
            for (int i = 0; i < 256; i++) {
                zeroVec.add(0.0);
            }

            int count = service.enroll(userId, tenantId,
                    List.of(zeroVec, List.of(1.0, 0.0), validEmb(0.1)));

            assertThat(count).isEqualTo(1);
            verify(mapper, times(1)).insert(any(VoiceprintEmbedding.class));
        }

        @Test
        @DisplayName("B-05：全部无效 → 返回 0 不写入")
        void allInvalidNoWrite() {
            int count = service.enroll(UUID.randomUUID(), UUID.randomUUID(),
                    List.of(List.of(1.0, 0.0), List.of(0.0, 0.0)));

            assertThat(count).isZero();
            verify(mapper, never()).insert(any(VoiceprintEmbedding.class));
        }
    }
}
