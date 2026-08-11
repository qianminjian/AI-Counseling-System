package com.mindsafe.service.device;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.Device;
import com.mindsafe.domain.mapper.DeviceMapper;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 设备安全服务（P0-1：report 通道签名鉴权基础设施）
 * <p>
 * 配网阶段（reportOnline）生成 device_secret（HMAC 密钥）并签发 device_token。
 * token 格式：{@code DVC_{deviceCode}_{expiresAtMillis}_{sig}}，
 * sig = HMAC-SHA256(secret, deviceCode + "|" + expiresAt)。
 * 校验（AUDIT-DEEP-001，2026-08-11）：格式解析 + 过期窗口（24h）+ 签名重算比对（防篡改/重放），
 * 旧 3 段格式（无 exp）回退等值比较兼容已签发设备。
 * 真实 HMAC-SHA256 请求签名+时间戳抗重放（固件侧请求体签名）待 NST-HW-02 二期对接后启用。
 */
@Service
public class DeviceSecurityService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration TOKEN_TTL = Duration.ofHours(24);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String TOKEN_PREFIX = "DVC_";

    private final DeviceMapper deviceMapper;

    public DeviceSecurityService(DeviceMapper deviceMapper) {
        this.deviceMapper = deviceMapper;
    }

    /**
     * reportOnline 时调用：生成 device_secret + 签发 device_token（内嵌过期时间 + HMAC 签名）。
     * device_secret 落库备签名验证；device_token 返回给设备端供后续报告鉴权。
     */
    public DeviceSecurityCredentials issueCredentials(Device device) {
        String secret = generateSecret();
        long expiresAt = Instant.now().plus(TOKEN_TTL).toEpochMilli();
        String token = TOKEN_PREFIX + device.getDeviceCode() + "_" + expiresAt + "_"
                + sign(device.getDeviceCode() + "|" + expiresAt, secret);

        Device update = new Device();
        update.setDeviceId(device.getDeviceId());
        update.setDeviceSecret(secret);
        update.setDeviceToken(token);
        deviceMapper.updateById(update);

        return new DeviceSecurityCredentials(token, expiresAt);
    }

    /**
     * 验证 device_token 有效性（AUDIT-DEEP-001）：格式 4 段（DVC_code_exp_sig）→
     * code 匹配 + 未过期（24h 窗口）+ 签名重算比对（防篡改/防重放）；
     * 旧 3 段格式（P0-1 早期签发）回退等值比较（兼容已上线设备，无过期语义）。
     */
    public boolean validateToken(String token, String deviceCode) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            return false;
        }
        Device device = deviceMapper.selectOne(
                new LambdaQueryWrapper<Device>()
                        .eq(Device::getDeviceCode, deviceCode)
                        .last("LIMIT 1"));
        if (device == null || device.getDeviceToken() == null) {
            return false;
        }

        String[] parts = token.split("_", 4);
        if (parts.length == 4) {
            // 新格式：DVC_{code}_{exp}_{sig}
            if (!parts[1].equals(deviceCode)) {
                return false;
            }
            long expiresAt;
            try {
                expiresAt = Long.parseLong(parts[2]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (Instant.now().toEpochMilli() > expiresAt) {
                return false; // 过期
            }
            if (device.getDeviceSecret() == null) {
                return false;
            }
            String expected = sign(deviceCode + "|" + expiresAt, device.getDeviceSecret());
            return constantTimeEquals(expected, parts[3]);
        }
        // 旧 3 段格式兼容：等值比较（P0-1 早期签发，无过期语义）
        return token.equals(device.getDeviceToken());
    }

    /** 常量时间比较（防时序攻击，AUDIT-DEEP-001） */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    public static String generateSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static String sign(String data, String key) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key.getBytes(), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException("HMAC 签名失败", e);
        }
    }

    public record DeviceSecurityCredentials(String token, long expiresAt) {}
}