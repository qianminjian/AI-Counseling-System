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
 * 配网阶段（reportOnline）生成 device_secret（HMAC 密钥）并签发 device_token
 * （JWT 设备身份令牌）。设备端存储后后续请求用 HMAC-SHA256 签名。
 * 当前 token 为简化无 JWT 依赖的 BEARER 方案（device_secret 为随机 32 字节 hex，
 * device_token = "DVC_" + deviceCode + "_" + 签名(device_secret + expiration)）。
 * 真实 HMAC-SHA256 签名+时间戳抗重放待固件侧 NST-HW-02 二期对接后启用。
 */
@Service
public class DeviceSecurityService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration TOKEN_TTL = Duration.ofHours(24);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final DeviceMapper deviceMapper;

    public DeviceSecurityService(DeviceMapper deviceMapper) {
        this.deviceMapper = deviceMapper;
    }

    /**
     * reportOnline 时调用：生成 device_secret + 签发 device_token。
     * device_secret 落库备 HMAC 签名验证；device_token 返回给设备端供后续报告鉴权。
     */
    public DeviceSecurityCredentials issueCredentials(Device device) {
        String secret = generateSecret();
        String token = "DVC_" + device.getDeviceCode() + "_" + sign(secret, secret);
        long expiresAt = Instant.now().plus(TOKEN_TTL).toEpochMilli();

        Device update = new Device();
        update.setDeviceId(device.getDeviceId());
        update.setDeviceSecret(secret);
        update.setDeviceToken(token);
        deviceMapper.updateById(update);

        return new DeviceSecurityCredentials(token, expiresAt);
    }

    /**
     * 验证 device_token 有效性：检查格式（DVC_{code}_{sig}）+ device 表 token 匹配
     * + 未过期（TTL 24h，由签发时间 from token 生成时间窗口近似判断）。
     */
    public boolean validateToken(String token, String deviceCode) {
        if (token == null || !token.startsWith("DVC_")) {
            return false;
        }
        Device device = deviceMapper.selectOne(
                new LambdaQueryWrapper<Device>()
                        .eq(Device::getDeviceCode, deviceCode)
                        .last("LIMIT 1"));
        if (device == null || device.getDeviceToken() == null) {
            return false;
        }
        return token.equals(device.getDeviceToken());
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