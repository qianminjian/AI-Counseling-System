package com.mindsafe.service.device;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.Device;
import com.mindsafe.domain.entity.DeviceBindCode;
import com.mindsafe.domain.entity.DeviceBinding;
import com.mindsafe.domain.mapper.DeviceBindCodeMapper;
import com.mindsafe.domain.mapper.DeviceBindingMapper;
import com.mindsafe.domain.mapper.DeviceMapper;
import com.mindsafe.domain.util.DeviceCodeUtil;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 无屏终端设备管理服务（CFG-001/004，doing/84 §六）
 * <p>
 * 覆盖：设备档案自动注册（首次上线）、心跳/状态上报、脱敏查询、
 * 绑定验证码会话（生成/校验/锁定）、绑定/解绑、配置拉取。
 * 状态机：UNACTIVATED → PROVISIONING → ONLINE_UNBOUND → ONLINE_BOUND（→ OFFLINE 惰性判定）。
 */
@Service
public class DeviceService {

    private final DeviceMapper deviceMapper;
    private final DeviceBindingMapper bindingMapper;
    private final DeviceBindCodeMapper bindCodeMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public DeviceService(DeviceMapper deviceMapper, DeviceBindingMapper bindingMapper,
                         DeviceBindCodeMapper bindCodeMapper) {
        this.deviceMapper = deviceMapper;
        this.bindingMapper = bindingMapper;
        this.bindCodeMapper = bindCodeMapper;
    }

    /**
     * 设备首次上线/回连检查上报（AC-84-24）：不存在则自动注册（UNACTIVATED → ONLINE_UNBOUND），
     * 已绑定保持 ONLINE_BOUND；更新最近在线时间与固件版本。
     */
    public Map<String, Object> reportOnline(String deviceCode, String sn, String firmwareVersion, String serverUrl) {
        if (deviceCode == null || !DeviceCodeUtil.isValid(deviceCode)) {
            throw new IllegalArgumentException("设备码不合法");
        }
        if (sn == null || sn.isBlank()) {
            throw new IllegalArgumentException("设备 SN 缺失");
        }
        Device device = findByCode(deviceCode);
        Instant now = Instant.now();
        if (device == null) {
            if (deviceMapper.selectCount(new LambdaQueryWrapper<Device>().eq(Device::getSn, sn)) > 0) {
                throw new IllegalArgumentException("该 SN 已被其他设备码占用");
            }
            device = new Device();
            device.setDeviceId(UUID.randomUUID());
            device.setDeviceCode(deviceCode);
            device.setSn(sn);
            device.setDeviceType("desk_toy");
            device.setFirmwareVersion(firmwareVersion);
            device.setServerUrl(serverUrl);
            device.setStatus(Device.STATUS_ONLINE_UNBOUND);
            device.setLastOnlineAt(now);
            device.setCreatedAt(now);
            device.setUpdatedAt(now);
            deviceMapper.insert(device);
        } else {
            if (device.getStatus().equals(Device.STATUS_RETIRED)) {
                throw new IllegalArgumentException("设备已注销");
            }
            boolean bound = device.getStatus().equals(Device.STATUS_ONLINE_BOUND);
            Device update = new Device();
            update.setDeviceId(device.getDeviceId());
            update.setFirmwareVersion(firmwareVersion != null ? firmwareVersion : device.getFirmwareVersion());
            if (serverUrl != null && !serverUrl.isBlank()) {
                update.setServerUrl(serverUrl);
            }
            update.setStatus(bound ? Device.STATUS_ONLINE_BOUND : Device.STATUS_ONLINE_UNBOUND);
            update.setLastOnlineAt(now);
            update.setUpdatedAt(now);
            deviceMapper.updateById(update);
            device.setStatus(update.getStatus());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceCode", deviceCode);
        result.put("status", device.getStatus());
        result.put("registered", true);
        return result;
    }

    /** 心跳上报：更新最近在线时间；设备不存在返回 null。 */
    public Void heartbeat(String deviceCode) {
        Device device = requireDevice(deviceCode);
        if (device.getStatus().equals(Device.STATUS_RETIRED)) {
            throw new IllegalArgumentException("设备已注销");
        }
        Device update = new Device();
        update.setDeviceId(device.getDeviceId());
        update.setLastOnlineAt(Instant.now());
        update.setUpdatedAt(Instant.now());
        deviceMapper.updateById(update);
        return null;
    }

    /** 状态上报：更新固件版本（电量/信号 P0 不入库，YAGNI）。 */
    public Void reportStatus(String deviceCode, String firmwareVersion) {
        Device device = requireDevice(deviceCode);
        if (firmwareVersion == null || firmwareVersion.isBlank()) {
            return null;
        }
        Device update = new Device();
        update.setDeviceId(device.getDeviceId());
        update.setFirmwareVersion(firmwareVersion);
        update.setUpdatedAt(Instant.now());
        deviceMapper.updateById(update);
        return null;
    }

    /**
     * 扫码入口页脱敏信息（匿名可调，AC-84-01）：型号/尾号/绑定态，不返回 SN。
     */
    public Map<String, Object> getDeviceInfo(String deviceCode) {
        Device device = requireDevice(deviceCode);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("deviceCode", deviceCode);
        info.put("deviceType", device.getDeviceType());
        info.put("codeTail", deviceCode.substring(Math.max(0, deviceCode.length() - 4)));
        info.put("bound", isBound(device));
        info.put("status", device.getStatus());
        return info;
    }

    /** 回连检查轮询（AC-84-04）：在线/离线（90s 心跳阈值惰性判定）+ 固件版本。 */
    public Map<String, Object> getDeviceStatus(String deviceCode) {
        Device device = requireDevice(deviceCode);
        boolean online = device.getLastOnlineAt() != null
                && device.getLastOnlineAt().isAfter(Instant.now().minusSeconds(Device.HEARTBEAT_TIMEOUT_SECONDS));
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("deviceCode", deviceCode);
        status.put("online", online);
        status.put("firmwareVersion", device.getFirmwareVersion());
        status.put("status", device.getStatus());
        return status;
    }

    /**
     * 生成绑定验证码会话（AC-84-23）：设备须处于未绑定态；返回明文一次（供设备
     * 语音播报），库中仅存 SHA-256 哈希；5 分钟有效。
     */
    public Map<String, Object> createBindCode(String deviceCode, String operator) {
        Device device = requireDevice(deviceCode);
        if (isBound(device)) {
            throw new IllegalArgumentException("设备已绑定，无需生成绑定码");
        }
        String plainCode = String.format("%06d", secureRandom.nextInt(1_000_000));
        DeviceBindCode record = new DeviceBindCode();
        record.setCodeId(UUID.randomUUID());
        record.setDeviceId(device.getDeviceId());
        record.setCodeHash(sha256Hex(plainCode));
        record.setExpiresAt(Instant.now().plusSeconds(DeviceBindCode.CODE_TTL_MINUTES * 60));
        record.setFailCount(0);
        record.setCreatedAt(Instant.now());
        bindCodeMapper.insert(record);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceCode", deviceCode);
        result.put("code", plainCode);
        result.put("expiresAt", record.getExpiresAt());
        return result;
    }

    /**
     * 绑定（AC-84-10/11/12）：登录态 + 验证码双因子；验证码哈希比对、
     * 3 次失败锁定 5 分钟、绑定成功即作废；设备状态 → ONLINE_BOUND。
     */
    public Map<String, Object> bind(String deviceCode, String bindType, UUID bindTargetId,
                                    UUID studentId, String code, String operator) {
        Device device = requireDevice(deviceCode);
        if (isBound(device)) {
            throw new IllegalArgumentException("设备已绑定");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("绑定验证码缺失");
        }
        DeviceBindCode latest = latestActiveCode(device.getDeviceId());
        if (latest == null) {
            throw new IllegalArgumentException("无有效绑定会话，请先发起绑定");
        }
        Instant now = Instant.now();
        if (latest.getLockedUntil() != null && latest.getLockedUntil().isAfter(now)) {
            throw new IllegalArgumentException("验证码已锁定，请稍后再试");
        }
        if (latest.getExpiresAt().isBefore(now)) {
            throw new IllegalArgumentException("验证码已过期，请重新发起绑定");
        }
        if (!latest.getCodeHash().equals(sha256Hex(code))) {
            int failCount = (latest.getFailCount() == null ? 0 : latest.getFailCount()) + 1;
            DeviceBindCode failUpdate = new DeviceBindCode();
            failUpdate.setCodeId(latest.getCodeId());
            failUpdate.setFailCount(failCount);
            if (failCount >= DeviceBindCode.MAX_FAIL_COUNT) {
                failUpdate.setLockedUntil(now.plusSeconds(DeviceBindCode.LOCK_MINUTES * 60));
            }
            bindCodeMapper.updateById(failUpdate);
            throw new IllegalArgumentException("验证码错误" + (failCount >= DeviceBindCode.MAX_FAIL_COUNT
                    ? "，已锁定 5 分钟" : "，剩余 " + (DeviceBindCode.MAX_FAIL_COUNT - failCount) + " 次机会"));
        }

        // 双因子通过：建立绑定 + 验证码作废 + 设备状态翻转
        DeviceBinding binding = new DeviceBinding();
        binding.setBindingId(UUID.randomUUID());
        binding.setDeviceId(device.getDeviceId());
        binding.setBindType(bindType);
        binding.setBindTargetId(bindTargetId);
        binding.setStudentId(studentId);
        binding.setBoundBy(operator);
        binding.setStatus(DeviceBinding.STATUS_ACTIVE);
        binding.setBoundAt(now);
        bindingMapper.insert(binding);

        DeviceBindCode used = new DeviceBindCode();
        used.setCodeId(latest.getCodeId());
        used.setUsedAt(now);
        bindCodeMapper.updateById(used);

        Device deviceUpdate = new Device();
        deviceUpdate.setDeviceId(device.getDeviceId());
        deviceUpdate.setStatus(Device.STATUS_ONLINE_BOUND);
        deviceUpdate.setUpdatedAt(now);
        deviceMapper.updateById(deviceUpdate);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceCode", deviceCode);
        result.put("status", Device.STATUS_ONLINE_BOUND);
        result.put("boundAt", now);
        return result;
    }

    /** 解绑（reason 由调用方审计，此处仅状态流转）：绑定置 UNBOUND，设备回 ONLINE_UNBOUND。 */
    public Map<String, Object> unbind(String deviceCode, String operator) {
        Device device = requireDevice(deviceCode);
        List<DeviceBinding> actives = bindingMapper.selectList(
                new LambdaQueryWrapper<DeviceBinding>()
                        .eq(DeviceBinding::getDeviceId, device.getDeviceId())
                        .eq(DeviceBinding::getStatus, DeviceBinding.STATUS_ACTIVE));
        Instant now = Instant.now();
        for (DeviceBinding binding : actives) {
            DeviceBinding update = new DeviceBinding();
            update.setBindingId(binding.getBindingId());
            update.setStatus(DeviceBinding.STATUS_UNBOUND);
            update.setUnboundAt(now);
            bindingMapper.updateById(update);
        }
        Device deviceUpdate = new Device();
        deviceUpdate.setDeviceId(device.getDeviceId());
        deviceUpdate.setStatus(Device.STATUS_ONLINE_UNBOUND);
        deviceUpdate.setUpdatedAt(now);
        deviceMapper.updateById(deviceUpdate);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceCode", deviceCode);
        result.put("status", Device.STATUS_ONLINE_UNBOUND);
        result.put("unboundAt", now);
        return result;
    }

    /** 配置拉取（心跳时调用）：返回服务器地址（P0 配置面，音色/心情随管理台 CFG-008 扩展）。 */
    public Map<String, Object> pullConfig(String deviceCode) {
        Device device = requireDevice(deviceCode);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("serverUrl", device.getServerUrl());
        config.put("heartbeatIntervalSeconds", 30L);
        return config;
    }

    /** 设备是否存在（供扫码页 404 分流）。 */
    public boolean exists(String deviceCode) {
        return deviceCode != null && findByCode(deviceCode) != null;
    }

    private Device requireDevice(String deviceCode) {
        Device device = findByCode(deviceCode);
        if (device == null) {
            throw new IllegalArgumentException("设备不存在");
        }
        return device;
    }

    private Device findByCode(String deviceCode) {
        return deviceMapper.selectOne(
                new LambdaQueryWrapper<Device>().eq(Device::getDeviceCode, deviceCode));
    }

    private boolean isBound(Device device) {
        return device.getStatus().equals(Device.STATUS_ONLINE_BOUND)
                || bindingMapper.selectCount(new LambdaQueryWrapper<DeviceBinding>()
                        .eq(DeviceBinding::getDeviceId, device.getDeviceId())
                        .eq(DeviceBinding::getStatus, DeviceBinding.STATUS_ACTIVE)) > 0;
    }

    private DeviceBindCode latestActiveCode(UUID deviceId) {
        return bindCodeMapper.selectOne(
                new LambdaQueryWrapper<DeviceBindCode>()
                        .eq(DeviceBindCode::getDeviceId, deviceId)
                        .isNull(DeviceBindCode::getUsedAt)
                        .orderByDesc(DeviceBindCode::getCreatedAt)
                        .last("LIMIT 1"));
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
