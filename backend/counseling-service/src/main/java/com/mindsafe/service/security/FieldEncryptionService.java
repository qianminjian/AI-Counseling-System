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

    /** 系统级启用开关（ENCRYPTION_ENABLED，默认 false=明文透传） */
    private final boolean enabled;

    /** 密钥版本 → SecretKeySpec 映射（支持轮换期间多版本解密） */
    private final Map<Integer, SecretKeySpec> keyRegistry = new ConcurrentHashMap<>();

    public FieldEncryptionService(
            @Value("${mindsafe.encryption.enabled:false}") boolean enabled,
            @Value("${mindsafe.encryption.key:}") String currentKey,
            @Value("${mindsafe.encryption.key-version:1}") int keyVersion,
            @Value("${mindsafe.encryption.previous-keys:}") String previousKeys,
            Environment environment) {

        this.enabled = enabled;
        this.activeKeyVersion = keyVersion;

        // 未启用：不校验密钥、不解析版本化变量，加解密纯透传（V1 语义：KEY_VERSION/PREVIOUS_KEYS 被忽略）
        if (!enabled) {
            // 防呆：密钥已配置但未启用 → 提示数据明文落库，防止误配后静默明文
            if (currentKey != null && !currentKey.isBlank()) {
                log.warn("加密未启用（ENCRYPTION_ENABLED=false）但检测到密钥已配置，数据将以明文落库。"
                        + "如需加密请设置 ENCRYPTION_ENABLED=true（商业化阶段要求，见 frozen/60 COMP-008）");
            }
            log.info("字段加密服务初始化: 未启用（明文模式）");
            return;
        }

        // 启用：fail-fast 校验密钥（替换原"prod profile 强制"语义，由显式开关裁决）
        if (currentKey == null || currentKey.isBlank()) {
            throw new IllegalStateException(
                "[FATAL] 字段加密已启用（ENCRYPTION_ENABLED=true）但未配置 MINDSAFE_ENCRYPTION_KEY，"
                + "敏感字段将明文存储。请配置 Base64 编码的 32 字节密钥后重启。");
        }
        keyRegistry.put(keyVersion, buildKey(currentKey));
        log.info("字段加密服务初始化: 已启用, activeKeyVersion={}", keyVersion);

        // 注册历史密钥（格式：version:base64key,version:base64key）—— 仅 enabled=true 时解析注册
        // 注：非法条目（版本号非整数/密钥非 32 字节）保持 fail-fast 抛错；版本号与 activeKeyVersion 冲突时
        // 后者覆盖前者（现状行为，轮换演练需避免相同版本号）
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
        if (!enabled || plaintext == null || plaintext.isBlank()) return plaintext;
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
        if (!enabled || ciphertext == null || ciphertext.isBlank()) return ciphertext;
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
