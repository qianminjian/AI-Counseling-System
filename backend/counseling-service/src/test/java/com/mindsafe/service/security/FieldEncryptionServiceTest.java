package com.mindsafe.service.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FieldEncryptionService 单元测试（COMP-005）
 */
class FieldEncryptionServiceTest {

    private FieldEncryptionService service;
    private static final String TEST_KEY_BASE64;

    static {
        // 生成一个合法的 256-bit 测试密钥
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < 32; i++) keyBytes[i] = (byte) i;
        TEST_KEY_BASE64 = Base64.getEncoder().encodeToString(keyBytes);
    }

    @BeforeEach
    void setUp() {
        service = new FieldEncryptionService(true, TEST_KEY_BASE64, 1, "", new StandardEnvironment());
    }

    @Test
    @DisplayName("加密后解密应还原原文")
    void encryptDecrypt_roundTrip() {
        String plaintext = "这是一段敏感的学生心理记录内容";
        String encrypted = service.encrypt(plaintext);

        assertNotEquals(plaintext, encrypted);
        assertTrue(encrypted.startsWith("v1:"));

        String decrypted = service.decrypt(encrypted);
        assertEquals(plaintext, decrypted);
    }

    @Test
    @DisplayName("null 和空字符串应透传")
    void encryptDecrypt_nullAndEmpty() {
        assertNull(service.encrypt(null));
        assertNull(service.decrypt(null));
        assertEquals("", service.encrypt(""));
        assertEquals("", service.decrypt(""));
    }

    @Test
    @DisplayName("非加密格式文本应原样返回（明文兼容）")
    void decrypt_plainTextPassthrough() {
        String plain = "这是未加密的旧数据";
        assertEquals(plain, service.decrypt(plain));
    }

    @Test
    @DisplayName("isEncrypted 正确识别加密格式")
    void isEncrypted_detection() {
        String encrypted = service.encrypt("test");
        assertTrue(service.isEncrypted(encrypted));
        assertFalse(service.isEncrypted("plain text"));
        assertFalse(service.isEncrypted(null));
        assertFalse(service.isEncrypted("version:1.0")); // colon 位置 > 5
    }

    @Test
    @DisplayName("密钥轮换：reEncrypt 使用新版本重新加密")
    void reEncrypt_keyRotation() {
        // v1 加密
        String v1Encrypted = service.encrypt("轮换测试数据");
        assertTrue(v1Encrypted.startsWith("v1:"));

        // 模拟 v2 服务（持有 v1 历史密钥 + v2 当前密钥）
        byte[] key2Bytes = new byte[32];
        for (int i = 0; i < 32; i++) key2Bytes[i] = (byte) (i + 100);
        String key2Base64 = Base64.getEncoder().encodeToString(key2Bytes);

        FieldEncryptionService v2Service = new FieldEncryptionService(
                true, key2Base64, 2, "1:" + TEST_KEY_BASE64, new StandardEnvironment());

        // reEncrypt: v1 密文 → v2 密文
        String v2Encrypted = v2Service.reEncrypt(v1Encrypted);
        assertTrue(v2Encrypted.startsWith("v2:"));

        // v2 能解密
        assertEquals("轮换测试数据", v2Service.decrypt(v2Encrypted));
    }

    @Test
    @DisplayName("加密未启用（ENCRYPTION_ENABLED=false）时明文透传，不产生 v 前缀密文")
    void disabled_plaintextPassthrough() {
        FieldEncryptionService disabledService = new FieldEncryptionService(false, "", 1, "", new StandardEnvironment());
        String plaintext = "未启用加密透传测试";
        assertEquals(plaintext, disabledService.encrypt(plaintext));
        assertEquals(plaintext, disabledService.decrypt(plaintext));
        assertFalse(disabledService.isEncrypted(plaintext));
    }

    @Test
    @DisplayName("加密未启用但密钥已配置：透传且不校验密钥格式（防呆 WARN）")
    void disabled_withKey_passthrough() {
        FieldEncryptionService disabledService = new FieldEncryptionService(
                false, "非法密钥字符串-not-base64-32bytes", 1, "", new StandardEnvironment());
        String plaintext = "防呆场景明文";
        assertEquals(plaintext, disabledService.encrypt(plaintext));
        assertEquals(plaintext, disabledService.decrypt(plaintext));
    }

    @Test
    @DisplayName("加密未启用时 previous-keys 非法条目被忽略，不抛错不注册（V1/V2 语义）")
    void disabled_ignoresInvalidPreviousKeys() {
        // 非法格式（非 version:base64key）与非法长度 key 混入，enabled=false 下必须全部忽略
        FieldEncryptionService disabledService = new FieldEncryptionService(
                false, "", 1, "bad-format-entry,2:" + Base64.getEncoder().encodeToString(new byte[16]),
                new StandardEnvironment());
        String plaintext = "版本化变量忽略测试";
        assertEquals(plaintext, disabledService.encrypt(plaintext));
    }

    @Test
    @DisplayName("加密启用但密钥未配置：fail-fast 拒绝启动（ENC-002）")
    void enabled_missingKey_failsFast() {
        assertThrows(IllegalStateException.class,
                () -> new FieldEncryptionService(true, "", 1, "", new StandardEnvironment()));
    }

    @Test
    @DisplayName("加密启用且密钥为空白：fail-fast 拒绝启动（ENC-002）")
    void enabled_blankKey_failsFast() {
        assertThrows(IllegalStateException.class,
                () -> new FieldEncryptionService(true, "   ", 1, "", new StandardEnvironment()));
    }

    @Test
    @DisplayName("每次加密产生不同密文（随机 IV）")
    void encrypt_randomIV() {
        String plaintext = "相同明文";
        String enc1 = service.encrypt(plaintext);
        String enc2 = service.encrypt(plaintext);
        assertNotEquals(enc1, enc2); // 不同 IV → 不同密文
        assertEquals(plaintext, service.decrypt(enc1));
        assertEquals(plaintext, service.decrypt(enc2));
    }

    @Test
    @DisplayName("加密启用且密钥非法长度应抛异常")
    void invalidKeyLength_throws() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]); // 128-bit
        assertThrows(IllegalArgumentException.class,
                () -> new FieldEncryptionService(true, shortKey, 1, "", new StandardEnvironment()));
    }
}
