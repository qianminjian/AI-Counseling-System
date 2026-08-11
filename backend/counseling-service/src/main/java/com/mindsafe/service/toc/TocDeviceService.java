package com.mindsafe.service.toc;

import com.mindsafe.domain.entity.DeviceBinding;
import com.mindsafe.domain.mapper.DeviceBindingMapper;
import com.mindsafe.domain.mapper.DeviceMapper;
import com.mindsafe.service.device.DeviceService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * toC 设备绑定服务（doing/85 TOC-003，toC-AC-3；联动 doing/84 CFG-010）
 * <p>
 * 家庭账号绑定无屏终端：复用 DeviceService 全链路（验证码会话/双因子/状态翻转），
 * bind_type=FAMILY（V39 预留）+ bind_target_id=家庭账号 ID + student_id=孩子档案 ID（单孩绑定）。
 */
@Service
public class TocDeviceService {

    private final DeviceService deviceService;
    private final DeviceBindingMapper bindingMapper;
    private final DeviceMapper deviceMapper;

    public TocDeviceService(DeviceService deviceService, DeviceBindingMapper bindingMapper,
                            DeviceMapper deviceMapper) {
        this.deviceService = deviceService;
        this.bindingMapper = bindingMapper;
        this.deviceMapper = deviceMapper;
    }

    /** 发起绑定验证码会话（触发设备语音播报，TOC-003）。 */
    public Map<String, Object> createBindCode(String deviceCode, String operator) {
        return deviceService.createBindCode(deviceCode, operator);
    }

    /**
     * 家庭绑定：设备码 + 验证码（+ 可选孩子档案 studentId）。
     */
    public Map<String, Object> bind(UUID familyAccountId, String deviceCode, UUID profileId,
                                    String code, String operator) {
        return deviceService.bind(deviceCode, DeviceBinding.BIND_TYPE_FAMILY,
                familyAccountId, profileId, code, operator);
    }

    /** 家庭解绑（TOC-003 解绑流程）。AD-004：校验设备属于该家庭后才允许解绑。 */
    public Map<String, Object> unbind(UUID familyAccountId, String deviceCode, String operator) {
        // AD-004：校验设备 ∈ 该家庭 ACTIVE 绑定（先解析 deviceCode→deviceId）
        com.mindsafe.domain.entity.Device device = deviceMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.mindsafe.domain.entity.Device>()
                        .eq(com.mindsafe.domain.entity.Device::getDeviceCode, deviceCode)
                        .last("LIMIT 1"));
        if (device == null) {
            throw new IllegalArgumentException("设备不存在");
        }
        long count = bindingMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DeviceBinding>()
                        .eq(DeviceBinding::getDeviceId, device.getDeviceId())
                        .eq(DeviceBinding::getBindTargetId, familyAccountId)
                        .eq(DeviceBinding::getBindType, DeviceBinding.BIND_TYPE_FAMILY)
                        .eq(DeviceBinding::getStatus, DeviceBinding.STATUS_ACTIVE));
        if (count == 0) {
            throw new IllegalArgumentException("该设备不属于当前家庭");
        }
        return deviceService.unbind(deviceCode, operator);
    }

    /** 家庭设备列表（本人账号绑定，TOC-003/CFG-010 家庭视图）。 */
    public List<Map<String, Object>> listDevices(UUID familyAccountId) {
        return deviceService.listDevices(DeviceBinding.BIND_TYPE_FAMILY, familyAccountId);
    }
}
