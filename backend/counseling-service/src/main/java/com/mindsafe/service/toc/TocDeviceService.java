package com.mindsafe.service.toc;

import com.mindsafe.domain.entity.DeviceBinding;
import com.mindsafe.service.device.DeviceService;
import org.springframework.stereotype.Service;

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

    public TocDeviceService(DeviceService deviceService) {
        this.deviceService = deviceService;
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

    /** 家庭解绑（TOC-003 解绑流程）。 */
    public Map<String, Object> unbind(UUID familyAccountId, String deviceCode, String operator) {
        return deviceService.unbind(deviceCode, operator);
    }

    /** 家庭设备列表（本人账号绑定，TOC-003/CFG-010 家庭视图）。 */
    public List<Map<String, Object>> listDevices(UUID familyAccountId) {
        return deviceService.listDevices(DeviceBinding.BIND_TYPE_FAMILY, familyAccountId);
    }
}
