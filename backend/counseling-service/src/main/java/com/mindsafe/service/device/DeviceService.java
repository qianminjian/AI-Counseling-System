package com.mindsafe.service.device;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.Device;
import com.mindsafe.domain.entity.DeviceOperation;
import com.mindsafe.domain.mapper.DeviceOperationMapper;
import com.mindsafe.domain.entity.DeviceBindCode;
import com.mindsafe.domain.entity.DeviceBinding;
import com.mindsafe.domain.mapper.DeviceBindCodeMapper;
import com.mindsafe.domain.mapper.DeviceBindingMapper;
import com.mindsafe.domain.mapper.DeviceMapper;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.util.DeviceCodeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceMapper deviceMapper;
    private final DeviceBindingMapper bindingMapper;
    private final DeviceBindCodeMapper bindCodeMapper;
    private final DevicePreferenceService preferenceService;
    private final DeviceSecurityService securityService;
    private final DeviceOperationMapper operationMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public DeviceService(DeviceMapper deviceMapper, DeviceBindingMapper bindingMapper,
                         DeviceBindCodeMapper bindCodeMapper,
                         DevicePreferenceService preferenceService,
                         DeviceSecurityService securityService,
                         DeviceOperationMapper operationMapper) {
        this.deviceMapper = deviceMapper;
        this.bindingMapper = bindingMapper;
        this.bindCodeMapper = bindCodeMapper;
        this.preferenceService = preferenceService;
        this.securityService = securityService;
        this.operationMapper = operationMapper;
    }

    /**
     * 设备首次上线/回连检查上报（AC-84-24）：不存在则自动注册（UNACTIVATED → ONLINE_UNBOUND），
     * 已绑定保持 ONLINE_BOUND；更新最近在线时间与固件版本。
     */
    public Map<String, Object> reportOnline(String deviceCode, String sn, String firmwareVersion, String serverUrl,
                                            String deviceToken) {
        if (deviceCode == null || !DeviceCodeUtil.isValid(deviceCode)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "设备码不合法");
        }
        if (sn == null || sn.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "设备 SN 缺失");
        }
        Device device = findByCode(deviceCode);
        Instant now = Instant.now();
        if (device == null) {
            if (deviceMapper.selectCount(new LambdaQueryWrapper<Device>().eq(Device::getSn, sn)) > 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "该 SN 已被其他设备码占用");
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
                throw new BizException(ErrorCode.PARAM_INVALID, "设备已注销");
            }
            // AUDIT-DEEP-002 code-review P0-1：已存在设备回连必须携带有效 DVC_ token——
            // 否则攻击者可匿名重签 token 后绕过 pullConfig 校验 + 轮换 secret 使真设备失联
            if (!securityService.validateToken(deviceToken, deviceCode)) {
                throw new BizException(ErrorCode.UNAUTHORIZED, "设备回连需携带有效设备 token");
            }
            boolean bound = device.getStatus().equals(Device.STATUS_ONLINE_BOUND);
            Device update = new Device();
            update.setDeviceId(device.getDeviceId());
            update.setFirmwareVersion(firmwareVersion != null ? firmwareVersion : device.getFirmwareVersion());
            // AUDIT-DEEP-002（P1-02）：serverUrl 仅允许首次设置（新增设备时写入）与已绑定设备拒绝匿名改写；
            // 已存在未绑定设备同样禁止改写（防配网阶段劫持链，code-review P1-1——重试仅回传相同地址无业务损失）
            if (serverUrl != null && !serverUrl.isBlank()
                    && (device.getServerUrl() == null || device.getServerUrl().isBlank())) {
                update.setServerUrl(serverUrl);
            }
            update.setStatus(bound ? Device.STATUS_ONLINE_BOUND : Device.STATUS_ONLINE_UNBOUND);
            update.setLastOnlineAt(now);
            update.setUpdatedAt(now);
            deviceMapper.updateById(update);
            device.setStatus(update.getStatus());
        }
        // P0-1：签发设备安全凭证（device_secret 落库 + device_token 返回）
        DeviceSecurityService.DeviceSecurityCredentials creds = securityService.issueCredentials(device);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceCode", deviceCode);
        result.put("status", device.getStatus());
        result.put("registered", true);
        result.put("deviceToken", creds.token());
        result.put("tokenExpiresAt", creds.expiresAt());
        return result;
    }

    /** 心跳上报：更新最近在线时间；设备不存在返回 null。 */
    public Void heartbeat(String deviceCode) {
        Device device = requireDevice(deviceCode);
        if (device.getStatus().equals(Device.STATUS_RETIRED)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "设备已注销");
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
            throw new BizException(ErrorCode.PARAM_INVALID, "设备已绑定，无需生成绑定码");
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
    @Transactional
    public Map<String, Object> bind(String deviceCode, String bindType, UUID bindTargetId,
                                    UUID studentId, String code, String operator) {
        Device device = requireDevice(deviceCode);
        if (isBound(device)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "设备已绑定");
        }
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "绑定验证码缺失");
        }
        DeviceBindCode latest = latestActiveCode(device.getDeviceId());
        if (latest == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "无有效绑定会话，请先发起绑定");
        }
        Instant now = Instant.now();
        if (latest.getLockedUntil() != null && latest.getLockedUntil().isAfter(now)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "验证码已锁定，请稍后再试");
        }
        if (latest.getExpiresAt().isBefore(now)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "验证码已过期，请重新发起绑定");
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
            throw new BizException(ErrorCode.PARAM_INVALID, "验证码错误" + (failCount >= DeviceBindCode.MAX_FAIL_COUNT
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

    /** 解绑：绑定置 UNBOUND，设备回 ONLINE_UNBOUND（P1-1 收敛：解绑写审计与 factoryReset 同单点）。 */
    public Map<String, Object> unbind(String deviceCode, String operator) {
        Device device = requireDevice(deviceCode);
        Instant now = Instant.now();
        int unbound = unbindAllBindings(device.getDeviceId(), now);
        Device deviceUpdate = new Device();
        deviceUpdate.setDeviceId(device.getDeviceId());
        deviceUpdate.setStatus(Device.STATUS_ONLINE_UNBOUND);
        deviceUpdate.setUpdatedAt(now);
        deviceMapper.updateById(deviceUpdate);
        // P1-1（板块03）：解绑写操作审计（与 ota/reboot/factory-reset 同一 auditOperation 单点）
        auditOperation(deviceCode, "unbind", operator, "解绑" + unbound + "个绑定");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceCode", deviceCode);
        result.put("status", Device.STATUS_ONLINE_UNBOUND);
        result.put("unboundAt", now);
        return result;
    }

    /**
     * 配置拉取（心跳时调用）：返回服务器地址 + 设备偏好下发（TOC-006 远程管理软件侧）。
     * AUDIT-DEEP-002（P1-02）：已绑定设备必须携带 DVC_ token（X-Device-Token），
     * 未绑定设备允许匿名（配网阶段拉配置语义）——防攻击者匿名探取任意设备 serverUrl。
     */
    public Map<String, Object> pullConfig(String deviceCode, String deviceToken) {
        Device device = requireDevice(deviceCode);
        if (Device.STATUS_ONLINE_BOUND.equals(device.getStatus())) {
            if (!securityService.validateToken(deviceToken, deviceCode)) {
                throw new BizException(ErrorCode.UNAUTHORIZED, "已绑定设备配置拉取需有效设备 token");
            }
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("serverUrl", device.getServerUrl());
        config.put("heartbeatIntervalSeconds", 30L);
        Map<String, Object> preferences = preferenceService.preferencesForPull(deviceCode);
        if (preferences != null) {
            config.put("preferences", preferences);
        }
        // AD-002：config/pull 为匿名通道（permitAll），不在此下发 deviceToken（防凭证泄露）
        // deviceToken 仅由 reportOnline 返回（设备上线时获取，后续请求走 DVC_ token）
        return config;
    }

    /** 固件升级受理（CFG-008 M13，AC-84-20）：登记操作意图（真实执行由固件侧）。 */
    public Map<String, Object> ota(String deviceCode, String operator) {
        Device device = requireDevice(deviceCode);
        auditOperation(deviceCode, "ota", operator, "操作已受理，固件侧执行待 NST-HW-02 二期对接");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceCode", deviceCode);
        result.put("action", "ota");
        result.put("operator", operator);
        result.put("acceptedAt", Instant.now());
        result.put("note", "操作已受理，固件侧执行待 NST-HW-02 二期对接");
        return result;
    }

    /** 远程重启受理（CFG-008 M13）。 */
    public Map<String, Object> reboot(String deviceCode, String operator) {
        Device device = requireDevice(deviceCode);
        // AD-001（2026-08-11）：补齐 reboot 审计接入（ota/factory-reset 已接入，reboot 原漏接）
        auditOperation(deviceCode, "reboot", operator, "操作已受理，固件侧执行待 NST-HW-02 二期对接");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceCode", deviceCode);
        result.put("action", "reboot");
        result.put("operator", operator);
        result.put("acceptedAt", Instant.now());
        return result;
    }

    /** 恢复出厂（CFG-008 M13，AC-84-19）：解绑全部绑定 + 设备状态回 UNACTIVATED。 */
    public Map<String, Object> factoryReset(String deviceCode, String operator) {
        Device device = requireDevice(deviceCode);
        Instant now = Instant.now();
        int unbound = unbindAllBindings(device.getDeviceId(), now);
        auditOperation(deviceCode, "factory-reset", operator, "解绑" + unbound + "个绑定 + 状态回 UNACTIVATED");
        Device deviceUpdate = new Device();
        deviceUpdate.setDeviceId(device.getDeviceId());
        deviceUpdate.setStatus(Device.STATUS_UNACTIVATED);
        deviceUpdate.setServerUrl(null);
        deviceUpdate.setUpdatedAt(now);
        deviceMapper.updateById(deviceUpdate);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceCode", deviceCode);
        result.put("status", Device.STATUS_UNACTIVATED);
        result.put("unboundCount", unbound);
        result.put("operator", operator);
        return result;
    }

    /** 设备是否存在（供扫码页 404 分流）。 */
    public boolean exists(String deviceCode) {
        return deviceCode != null && findByCode(deviceCode) != null;
    }

    /**
     * 老师租户级设备列表（CFG-008，doing/84 §四.6）：按绑定归属过滤
     * （bind_type + bind_target_id 匹配老师学校/班级/咨询室）。
     */
    public List<Map<String, Object>> listDevices(String bindType, UUID bindTargetId) {
        List<DeviceBinding> actives = bindingMapper.selectList(
                new LambdaQueryWrapper<DeviceBinding>()
                        .eq(DeviceBinding::getBindType, bindType)
                        .eq(DeviceBinding::getBindTargetId, bindTargetId)
                        .eq(DeviceBinding::getStatus, DeviceBinding.STATUS_ACTIVE));
        if (actives.isEmpty()) {
            return List.of();
        }
        List<UUID> deviceIds = actives.stream().map(DeviceBinding::getDeviceId).toList();
        List<Device> devices = deviceMapper.selectList(
                new LambdaQueryWrapper<Device>().in(Device::getDeviceId, deviceIds));
        return devices.stream().map(d -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deviceCode", d.getDeviceCode());
            item.put("deviceType", d.getDeviceType());
            item.put("firmwareVersion", d.getFirmwareVersion());
            item.put("status", d.getStatus());
            item.put("online", d.getLastOnlineAt() != null
                    && d.getLastOnlineAt().isAfter(Instant.now().minusSeconds(Device.HEARTBEAT_TIMEOUT_SECONDS)));
            item.put("lastOnlineAt", d.getLastOnlineAt());
            return item;
        }).toList();
    }

    private Device requireDevice(String deviceCode) {
        Device device = findByCode(deviceCode);
        if (device == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "设备不存在");
        }
        return device;
    }

    /**
     * P1-1（板块03）解绑收敛单点：将设备全部 ACTIVE 绑定置 UNBOUND（unbind/factoryReset 共用），
     * 返回解绑数量。审计由调用方各自触发（action 不同：unbind / factory-reset）。
     */
    private int unbindAllBindings(UUID deviceId, Instant now) {
        List<DeviceBinding> actives = bindingMapper.selectList(
                new LambdaQueryWrapper<DeviceBinding>()
                        .eq(DeviceBinding::getDeviceId, deviceId)
                        .eq(DeviceBinding::getStatus, DeviceBinding.STATUS_ACTIVE));
        for (DeviceBinding binding : actives) {
            DeviceBinding update = new DeviceBinding();
            update.setBindingId(binding.getBindingId());
            update.setStatus(DeviceBinding.STATUS_UNBOUND);
            update.setUnboundAt(now);
            bindingMapper.updateById(update);
        }
        return actives.size();
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
                        .last("LIMIT 1 FOR UPDATE"));  // P0-2：行锁防竞态
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** P1 设备操作审计落库 */
    private void auditOperation(String deviceCode, String action, String operator, String note) {
        DeviceOperation op = new DeviceOperation();
        op.setOperationId(UUID.randomUUID());
        op.setDeviceCode(deviceCode);
        op.setAction(action);
        op.setOperator(operator);
        op.setAcceptedAt(Instant.now());
        op.setNote(note);
        operationMapper.insert(op);
    }
}
