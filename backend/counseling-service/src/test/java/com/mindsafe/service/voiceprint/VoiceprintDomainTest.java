package com.mindsafe.service.voiceprint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VoiceprintDomain 纯函数单元测试（DC-006，doing/72 §20）
 * <p>
 * 覆盖：余弦相似度边界 / 指纹确定性 / XFF 多级解析 / JSON 往返与损坏兜底。
 */
class VoiceprintDomainTest {

    @Nested
    @DisplayName("cosineSimilarity 余弦相似度")
    class CosineSimilarity {

        @Test
        @DisplayName("同向向量 → 1.0")
        void sameDirection() {
            assertThat(VoiceprintDomain.cosineSimilarity(List.of(1.0, 0.0), List.of(1.0, 0.0)))
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("正交向量 → 0.0")
        void orthogonal() {
            assertThat(VoiceprintDomain.cosineSimilarity(List.of(1.0, 0.0), List.of(0.0, 1.0)))
                    .isEqualTo(0.0);
        }

        @Test
        @DisplayName("反向向量 → -1.0")
        void oppositeDirection() {
            assertThat(VoiceprintDomain.cosineSimilarity(List.of(1.0, 0.0), List.of(-1.0, 0.0)))
                    .isEqualTo(-1.0);
        }

        @Test
        @DisplayName("长度不等 → 0（不抛异常）")
        void lengthMismatch() {
            assertThat(VoiceprintDomain.cosineSimilarity(List.of(1.0, 0.0), List.of(1.0))).isEqualTo(0.0);
        }

        @Test
        @DisplayName("null 参数 → 0")
        void nullArgs() {
            assertThat(VoiceprintDomain.cosineSimilarity(null, List.of(1.0))).isEqualTo(0.0);
            assertThat(VoiceprintDomain.cosineSimilarity(List.of(1.0), null)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("空列表 → 0")
        void emptyLists() {
            assertThat(VoiceprintDomain.cosineSimilarity(List.of(), List.of())).isEqualTo(0.0);
        }

        @Test
        @DisplayName("零向量（norm=0）→ 0（分母为 0 不抛异常）")
        void zeroVector() {
            assertThat(VoiceprintDomain.cosineSimilarity(List.of(0.0, 0.0), List.of(1.0, 1.0)))
                    .isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("fingerprint 指纹（AUD-001 重放防护 key）")
    class Fingerprint {

        @Test
        @DisplayName("同输入两次 → 同一指纹（确定性）")
        void deterministic() {
            List<List<Double>> emb = List.of(List.of(1.0, 0.0), List.of(0.5, 0.5));
            assertThat(VoiceprintDomain.fingerprint(emb))
                    .isEqualTo(VoiceprintDomain.fingerprint(emb));
        }

        @Test
        @DisplayName("指纹前缀 fp: + 64 位 hex（SHA-256）")
        void prefixAndLength() {
            String fp = VoiceprintDomain.fingerprint(List.of(List.of(1.0, 0.0)));
            assertThat(fp).startsWith("fp:");
            assertThat(fp.substring(3)).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("不同输入 → 不同指纹")
        void differentInputs() {
            assertThat(VoiceprintDomain.fingerprint(List.of(List.of(1.0, 0.0))))
                    .isNotEqualTo(VoiceprintDomain.fingerprint(List.of(List.of(0.0, 1.0))));
        }

        @Test
        @DisplayName("同内容不同封装 → 相同指纹（重放语义：内容一致即同一请求）")
        void sameContentSameFingerprint() {
            assertThat(VoiceprintDomain.fingerprint(List.of(List.of(1.0, 0.0))))
                    .isEqualTo(VoiceprintDomain.fingerprint(List.of(List.of(1.0, 0.0))));
        }
    }

    @Nested
    @DisplayName("resolveClientIp IP 解析（P0-3 防伪造）")
    class ResolveClientIp {

        private MockHttpServletRequest request(String remoteAddr) {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.setRemoteAddr(remoteAddr);
            return req;
        }

        @Test
        @DisplayName("XFF 多级：取最右条目（nginx $proxy_add_x_forwarded_for 追加的真实 IP）")
        void rightMostXffEntry() {
            MockHttpServletRequest req = request("172.17.0.2");
            req.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8");
            assertThat(VoiceprintDomain.resolveClientIp(req)).isEqualTo("5.6.7.8");
        }

        @Test
        @DisplayName("XFF 单值 → 返回该值")
        void singleXffValue() {
            MockHttpServletRequest req = request("172.17.0.2");
            req.addHeader("X-Forwarded-For", "1.2.3.4");
            assertThat(VoiceprintDomain.resolveClientIp(req)).isEqualTo("1.2.3.4");
        }

        @Test
        @DisplayName("无 XFF → 使用 remoteAddr")
        void noXffUsesRemoteAddr() {
            assertThat(VoiceprintDomain.resolveClientIp(request("10.0.0.9"))).isEqualTo("10.0.0.9");
        }

        @Test
        @DisplayName("XFF 空白 → 使用 remoteAddr")
        void blankXffUsesRemoteAddr() {
            MockHttpServletRequest req = request("10.0.0.9");
            req.addHeader("X-Forwarded-For", "   ");
            assertThat(VoiceprintDomain.resolveClientIp(req)).isEqualTo("10.0.0.9");
        }

        @Test
        @DisplayName("XFF 尾随逗号 → 取最右段并 trim")
        void trailingCommaTrimmed() {
            MockHttpServletRequest req = request("172.17.0.2");
            req.addHeader("X-Forwarded-For", "1.2.3.4,");
            assertThat(VoiceprintDomain.resolveClientIp(req)).isEqualTo("1.2.3.4");
        }
    }

    @Nested
    @DisplayName("isValidEmbedding / norm 校验（B-05：拒绝退化向量）")
    class EmbeddingValidation {

        private List<Double> vec(int dim, double fill) {
            List<Double> v = new ArrayList<>();
            for (int i = 0; i < dim; i++) {
                v.add(fill);
            }
            return v;
        }

        @Test
        @DisplayName("归一化 256 维向量（范数≈1）→ 通过")
        void acceptsNormalized256() {
            assertThat(VoiceprintDomain.isValidEmbedding(vec(256, 1.0 / 16.0))).isTrue();
        }

        @Test
        @DisplayName("维度不符（128 维）→ 拒绝")
        void rejectsWrongDimension() {
            assertThat(VoiceprintDomain.isValidEmbedding(vec(128, 1.0))).isFalse();
            assertThat(VoiceprintDomain.isValidEmbedding(List.of(1.0, 0.0))).isFalse();
        }

        @Test
        @DisplayName("零向量（范数 0）→ 拒绝")
        void rejectsZeroVector() {
            assertThat(VoiceprintDomain.isValidEmbedding(vec(256, 0.0))).isFalse();
        }

        @Test
        @DisplayName("null → 拒绝")
        void rejectsNull() {
            assertThat(VoiceprintDomain.isValidEmbedding(null)).isFalse();
        }

        @Test
        @DisplayName("norm 计算（3/4/5 直角三角形）")
        void normComputes() {
            assertThat(VoiceprintDomain.norm(List.of(3.0, 4.0))).isEqualTo(5.0);
            assertThat(VoiceprintDomain.norm(List.of(0.0, 0.0))).isZero();
        }
    }

    @Nested
    @DisplayName("toJson / parseEmbedding 编解码")
    class JsonCodec {

        @Test
        @DisplayName("往返一致：toJson → parseEmbedding 恢复原向量")
        void roundTrip() {
            List<Double> emb = List.of(0.123456789, -0.5, 0.0);
            assertThat(VoiceprintDomain.parseEmbedding(VoiceprintDomain.toJson(emb))).isEqualTo(emb);
        }

        @Test
        @DisplayName("parseEmbedding 损坏 JSON → null（C4：不吞没不扩散）")
        void corruptedJsonReturnsNull() {
            assertThat(VoiceprintDomain.parseEmbedding("{not-json")).isNull();
            assertThat(VoiceprintDomain.parseEmbedding("[1.0, \"oops\"")).isNull();
        }

        @Test
        @DisplayName("parseEmbedding(null) → null")
        void nullJsonReturnsNull() {
            assertThat(VoiceprintDomain.parseEmbedding(null)).isNull();
        }

        @Test
        @DisplayName("toJson 输出可被标准解析（[1.0,2.0] 形态）")
        void jsonShape() {
            assertThat(VoiceprintDomain.toJson(List.of(1.0, 2.0))).isEqualTo("[1.0,2.0]");
        }
    }
}
