package com.mindsafe.service.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 字段级加密服务（COMP-005：AES-256-GCM + 密钥版本化）
 * <p>
 * 设计：
 * - 算法：AES/GCM/NoPadding（认证加密，防篡改）
 * - 密钥：环境变量注入，Base64 编码的 256-bit 密钥
 * - 密钥轮换：密文前缀 "v{version}:" 标识加密时的密钥版本
 * - 解密时根据前缀选择对应版本密钥（支持多版本并存）
 * <p>
 * 适用字段：message_summaries.content_summary, teacher_notes.content,
 * student_profiles 敏感字段等。
 */
@Service
public class FieldEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(FieldEncryptionService.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;   // 96 bits
    private static final int GCM_TAG_LENGTH = 128; // 128 bits auth tag
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 当前活跃密钥版本 */
    private final int activeKeyVersion;

    /** 密钥版本 → SecretKeySpec 映射（支持轮换期间多版本解密） */
    private final Map<Integer, SecretKeySpec> keyRegistry = new ConcurrentHashMap<>();

    public FieldEncryptionService(
            @Value("${mindsafe.encryption.key:}") String currentKey,
            @Value("${mindsafe.encryption.key-version:1}") int keyVersion,
            @Value("${mindsafe.encryption.previous-keys:}") String previousKeys,
            Environment environment) {

        this.activeKeyVersion = keyVersion;

        // 注册当前密钥
        if (currentKey != null && !currentKey.isBlank()) {
            keyRegistry.put(keyVersion, buildKey(currentKey));
            log.info("字段加密服务初始化: activeKeyVersion={}", keyVersion);
        } else {
            // 生产环境 fail-fast：密钥未配置时拒绝启动
            boolean isProd = java.util.Arrays.asList(environment.getActiveProfiles()).contains("prod");
            if (isProd) {
                throw new IllegalStateException(
                    "[FATAL] 生产环境必须配置 MINDSAFE_ENCRYPTION_KEY（mindsafe.encryption.key），" +
                    "否则敏感字段将明文存储。请设置环境变量后重启。");
            }
            log.warn("字段加密密钥未配置（mindsafe.encryption.key），加密功能降级为明文透传（仅限开发环境）");
        }

        // 注册历史密钥（格式：version:base64key,version:base64key）
        if (previousKeys != null && !previousKeys.isBlank()) {
            for (String entry : previousKeys.split(",")) {
                String[] parts = entry.trim().split(":", 2);
                if (parts.length == 2) {
                    int ver = Integer.parseInt(parts[0].trim());
                    keyRegistry.put(ver, buildKey(parts[1].trim()));
                    log.info("注册历史密钥版本: v{}", ver);
                }
            }
        }
    }

    /**
     * 加密明文（返回带版本前缀的密文）
     * 格式：v1:<base64(iv + ciphertext + tag)>
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return plaintext;
        SecretKeySpec key = keyRegistry.get(activeKeyVersion);
        if (key == null) return plaintext; // 降级：未配置密钥时透传

        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // iv + ciphertext 拼接后 base64
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return "v" + activeKeyVersion + ":" + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // fail-fast：密钥已配置但加密异常（JCE 异常等），绝不允许敏感字段静默落库明文
            log.error("字段加密失败(fail-fast，拒绝明文降级)", e);
            throw new IllegalStateException("字段加密失败", e);
        }
    }

    /**
     * 解密密文（自动识别密钥版本）
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) return ciphertext;
        // 非加密格式（无版本前缀）→ 明文兼容
        if (!ciphertext.startsWith("v") || !ciphertext.contains(":")) return ciphertext;

        try {
            int colonIdx = ciphertext.indexOf(':');
            int version = Integer.parseInt(ciphertext.substring(1, colonIdx));
            String base64Data = ciphertext.substring(colonIdx + 1);

            SecretKeySpec key = keyRegistry.get(version);
            if (key == null) {
                log.warn("密钥版本不存在: v{}，无法解密", version);
                return "[ENCRYPTED]";
            }

            byte[] combined = Base64.getDecoder().decode(base64Data);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] encrypted = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            System.arraycopy(combined, iv.length, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plaintext = cipher.doFinal(encrypted);

            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("字段解密失败", e);
            return "[DECRYPT_ERROR]";
        }
    }

    /**
     * 判断是否为加密格式
     */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith("v") && value.contains(":")
                && value.indexOf(':') < 5; // v999: 最多 4 位版本号
    }

    /**
     * 密钥轮换：重新加密（用旧版本解密 → 新版本加密）
     */
    public String reEncrypt(String ciphertext) {
        String plaintext = decrypt(ciphertext);
        if (plaintext.equals("[ENCRYPTED]") || plaintext.equals("[DECRYPT_ERROR]")) {
            return ciphertext; // 无法解密，保持原样
        }
        return encrypt(plaintext);
    }

    private SecretKeySpec buildKey(String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("加密密钥必须为 256-bit（32 字节），当前: " + keyBytes.length);
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}
