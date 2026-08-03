package com.mindsafe.service.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;

/**
 * 画像维度 JSONB 元数据工具（PROF-025 / MEM-101 共用）
 * <p>
 * 在维度 JSONB 的 {@code _meta.<field>} 下盖字段级元数据戳
 * （provenance / confidence / evidence_count / updated_at / last_seen_at），不改表结构。
 * confidence 随证据次数收敛：evidence/(evidence+2)（1次→0.33，2次→0.5，5次→0.71），
 * 与编排门槛 MIN_CONFIDENCE=0.5 对齐：至少 2 次独立证据后才参与策略微调。
 */
final class ProfileMetaStamper {

    private ProfileMetaStamper() {
    }

    /** 盖字段级元数据戳（evidence_count 自增，provenance 由来源方指定） */
    static void stamp(ObjectNode dimension, String field, String provenance) {
        ObjectNode meta = dimension.has("_meta") && dimension.get("_meta").isObject()
                ? (ObjectNode) dimension.get("_meta")
                : dimension.putObject("_meta");
        ObjectNode entry = meta.has(field) && meta.get(field).isObject()
                ? (ObjectNode) meta.get(field)
                : meta.putObject(field);
        int evidence = entry.path("evidence_count").asInt(0) + 1;
        String now = Instant.now().toString();
        entry.put("provenance", provenance);
        entry.put("evidence_count", evidence);
        entry.put("confidence", Math.round(evidence / (evidence + 2.0) * 100.0) / 100.0);
        entry.put("updated_at", now);
        entry.put("last_seen_at", now);
    }

    /** 解析 JSON 对象；非法/空则返回空对象节点 */
    static ObjectNode parseObject(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.isObject() ? (ObjectNode) node : objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}
