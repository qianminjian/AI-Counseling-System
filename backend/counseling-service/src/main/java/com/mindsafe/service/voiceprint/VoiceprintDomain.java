package com.mindsafe.service.voiceprint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;

import java.security.MessageDigest;
import java.util.List;

/**
 * 声纹域纯函数（DC-006，doing/72 §20）
 * <p>
 * 从 VoiceprintController 下沉的零 Spring 依赖静态工具：
 * <ul>
 *   <li>{@link #cosineSimilarity}：1:N 比对的相似度计算（长度不等/空 → 0）</li>
 *   <li>{@link #fingerprint}：embedding 指纹（SHA-256），重放限流 key（AUD-001）</li>
 *   <li>{@link #resolveClientIp}：XFF 最右条目解析（P0-3 防伪造）</li>
 *   <li>{@link #toJson} / {@link #parseEmbedding}：embedding 存储编解码（损坏 → null，C4）</li>
 * </ul>
 */
public final class VoiceprintDomain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VoiceprintDomain() {
    }

    /**
     * 余弦相似度。长度不等 / null / 空 / 零向量 → 0（不抛异常，损坏数据不扩散）。
     */
    public static double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.size() != b.size()) {
            return 0;
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    /**
     * embedding 指纹（SHA-256），作重放限流 key（AUD-001）。
     * 序列化后摘要，同一请求重放 key 一致；浮点噪声使正常重录不受影响。
     */
    public static String fingerprint(List<List<Double>> embeddings) {
        try {
            byte[] json = MAPPER.writeValueAsBytes(embeddings);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            StringBuilder hex = new StringBuilder("fp:");
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            // 指纹计算失败不阻断流程：退化为按请求体 hashCode（同一对象引用重放仍可命中）
            return "fp:fallback:" + embeddings.hashCode();
        }
    }

    /**
     * 解析客户端 IP（P0-3 防伪造）：
     * - 经 nginx 代理：X-Forwarded-For 取最右条目——nginx 用 $proxy_add_x_forwarded_for
     *   在真实客户端 IP 前追加客户端提供的头，最右 = 不可伪造的真实 IP
     * - 直连：使用 remoteAddr
     */
    public static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // 从右往左取第一个非空段（尾随逗号/重复逗号等脏数据 → 跳过空段）
            String[] entries = forwarded.split(",");
            for (int i = entries.length - 1; i >= 0; i--) {
                String entry = entries[i].trim();
                if (!entry.isEmpty()) {
                    return entry;
                }
            }
        }
        return request.getRemoteAddr();
    }

    /** embedding 存储序列化（失败抛业务异常，调用方可见） */
    public static String toJson(List<Double> embedding) {
        try {
            return MAPPER.writeValueAsString(embedding);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "embedding 序列化失败");
        }
    }

    /** embedding 存储反序列化（损坏 → null，C4：记录级跳过由调用方处理） */
    public static List<Double> parseEmbedding(String json) {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Double>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }
}
