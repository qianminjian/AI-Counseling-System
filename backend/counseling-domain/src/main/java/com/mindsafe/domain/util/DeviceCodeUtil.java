package com.mindsafe.domain.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 设备短码工具（doing/84 §5.2.1 deviceCode 编码规则）
 * <p>
 * 组成：10 位 base32 短码（SN 经 SHA-256 派生，去 I/O/0/1 易混字符）+ 1 位
 * Luhn mod-32 校验位，共 11 位（如 K7M2P9XW4AQ）。校验位供前端/云端本地
 * 校验码合法性，防手输错误；短码不可逆推 SN。
 */
public final class DeviceCodeUtil {

    /** 易混字符剔除后的 base32 字母表（32 字符，大写） */
    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** 短码总长度（10 位内容 + 1 位校验位） */
    public static final int CODE_LENGTH = 11;

    /** 内容长度（不含校验位） */
    private static final int PAYLOAD_LENGTH = 10;

    private DeviceCodeUtil() {
    }

    /**
     * 由出厂 SN 派生设备短码（确定性：同一 SN 恒生成同一短码）。
     *
     * @param sn 出厂序列号（如 BB-2026-000123）
     * @return 11 位短码
     */
    public static String generate(String sn) {
        byte[] digest = sha256(sn);
        StringBuilder sb = new StringBuilder(PAYLOAD_LENGTH);
        // 取 SHA-256 前 50 bit（10 位 × 5bit）编码为 base32 内容
        long acc = 0;
        int bits = 0;
        for (byte b : digest) {
            acc = (acc << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5 && sb.length() < PAYLOAD_LENGTH) {
                int idx = (int) ((acc >> (bits - 5)) & 0x1F);
                sb.append(ALPHABET.charAt(idx));
                bits -= 5;
            }
            if (sb.length() >= PAYLOAD_LENGTH) {
                break;
            }
        }
        return sb.append(checkDigit(sb.toString())).toString();
    }

    /**
     * 校验短码合法性（长度、字符集、校验位）。
     */
    public static boolean isValid(String deviceCode) {
        if (deviceCode == null || deviceCode.length() != CODE_LENGTH) {
            return false;
        }
        String payload = deviceCode.substring(0, PAYLOAD_LENGTH);
        for (int i = 0; i < payload.length(); i++) {
            if (ALPHABET.indexOf(payload.charAt(i)) < 0) {
                return false;
            }
        }
        // 完整串 Luhn mod-32：从右往左，偶数位（自最右起第 2、4...位）值 ×2，加权和 mod 32 == 0
        return luhnWeightedSum(deviceCode) % 32 == 0;
    }

    /**
     * Luhn mod-32 校验位：校验位置于最右（posFromRight=1，×1），payload 各字符
     * 在完整串中的位置整体右移一位参与加权，使完整串加权和 mod 32 == 0。
     */
    static char checkDigit(String payload) {
        int sum = 0;
        for (int i = payload.length() - 1; i >= 0; i--) {
            int value = ALPHABET.indexOf(payload.charAt(i));
            int posFromRightInFull = (payload.length() - i) + 1; // 校验位占 posFromRight=1
            if (posFromRightInFull % 2 == 0) {
                value = (value * 2) % 32;
            }
            sum += value;
        }
        return ALPHABET.charAt((32 - (sum % 32)) % 32);
    }

    /** 完整串 Luhn mod-32 加权和（从右往左，偶数位 ×2 mod 32）。 */
    private static int luhnWeightedSum(String code) {
        int sum = 0;
        for (int i = code.length() - 1; i >= 0; i--) {
            int value = ALPHABET.indexOf(code.charAt(i));
            int posFromRight = code.length() - i; // 1-based
            if (posFromRight % 2 == 0) {
                value = (value * 2) % 32;
            }
            sum += value;
        }
        return sum;
    }

    private static byte[] sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 供测试断言使用的十六进制摘要（无业务用途，仅调试） */
    static String hexDigest(String input) {
        return HexFormat.of().formatHex(sha256(input));
    }
}
