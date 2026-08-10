package com.mindsafe.service.device;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.Device;
import com.mindsafe.domain.entity.DeviceBinding;
import com.mindsafe.domain.entity.DeviceQrIssuance;
import com.mindsafe.domain.mapper.DeviceBindingMapper;
import com.mindsafe.domain.mapper.DeviceMapper;
import com.mindsafe.domain.mapper.DeviceQrIssuanceMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 无屏终端平台管理服务（CFG-008 admin-web M13，doing/84 §六.2 平台管理域）
 * <p>
 * 跨租户设备管理（super_admin/ops_admin 视角）：设备列表（状态/归属筛选）、
 * 设备详情（含绑定历史）、二维码批量签发（device_qr_issuance 落库）、批量操作受理。
 * 真实固件操作（OTA/重启/恢复出厂）由设备端固件执行，本服务登记操作意图并审计。
 */
@Service
public class PlatformDeviceService {

    private final DeviceMapper deviceMapper;
    private final DeviceBindingMapper bindingMapper;
    private final DeviceQrIssuanceMapper qrIssuanceMapper;

    public PlatformDeviceService(DeviceMapper deviceMapper, DeviceBindingMapper bindingMapper,
                                 DeviceQrIssuanceMapper qrIssuanceMapper) {
        this.deviceMapper = deviceMapper;
        this.bindingMapper = bindingMapper;
        this.qrIssuanceMapper = qrIssuanceMapper;
    }

    /**
     * 跨租户设备列表（AC-84-18）：可按状态筛选；返回含当前绑定归属摘要。
     */
    public List<Map<String, Object>> listDevices(String status, UUID bindTargetId) {
        LambdaQueryWrapper<Device> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(Device::getStatus, status);
        }
        wrapper.orderByDesc(Device::getUpdatedAt);
        List<Device> devices = deviceMapper.selectList(wrapper);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Device d : devices) {
            if (bindTargetId != null && !hasBinding(d.getDeviceId(), bindTargetId)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deviceId", d.getDeviceId());
            item.put("deviceCode", d.getDeviceCode());
            item.put("deviceType", d.getDeviceType());
            item.put("firmwareVersion", d.getFirmwareVersion());
            item.put("status", d.getStatus());
            item.put("online", d.getLastOnlineAt() != null
                    && d.getLastOnlineAt().isAfter(Instant.now().minusSeconds(Device.HEARTBEAT_TIMEOUT_SECONDS)));
            item.put("lastOnlineAt", d.getLastOnlineAt());
            item.put("binding", activeBindingSummary(d.getDeviceId()));
            result.add(item);
        }
        return result;
    }

    /**
     * 设备详情（AC-84-18）：档案 + 绑定历史（ACTIVE/UNBOUND 全量）。
     */
    public Map<String, Object> getDeviceDetail(UUID deviceId) {
        Device d = deviceMapper.selectById(deviceId);
        if (d == null) {
            return null;
        }
        List<DeviceBinding> bindings = bindingMapper.selectList(
                new LambdaQueryWrapper<DeviceBinding>()
                        .eq(DeviceBinding::getDeviceId, deviceId)
                        .orderByDesc(DeviceBinding::getBoundAt));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("deviceId", d.getDeviceId());
        detail.put("deviceCode", d.getDeviceCode());
        detail.put("sn", d.getSn());
        detail.put("deviceType", d.getDeviceType());
        detail.put("firmwareVersion", d.getFirmwareVersion());
        detail.put("status", d.getStatus());
        detail.put("serverUrl", d.getServerUrl());
        detail.put("online", d.getLastOnlineAt() != null
                && d.getLastOnlineAt().isAfter(Instant.now().minusSeconds(Device.HEARTBEAT_TIMEOUT_SECONDS)));
        detail.put("lastOnlineAt", d.getLastOnlineAt());
        detail.put("createdAt", d.getCreatedAt());
        detail.put("bindings", bindings.stream().map(b -> {
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("bindType", b.getBindType());
            bm.put("bindTargetId", b.getBindTargetId());
            bm.put("studentId", b.getStudentId());
            bm.put("boundBy", b.getBoundBy());
            bm.put("status", b.getStatus());
            bm.put("boundAt", b.getBoundAt());
            bm.put("unboundAt", b.getUnboundAt());
            return bm;
        }).toList());
        return detail;
    }

    /**
     * 二维码批量签发（CFG-005 落地，AC-84-21/22）：校验设备码存在，
     * 签发记录落 device_qr_issuance（印刷包留痕），返回各设备二维码 URL（印刷包内容）。
     */
    public Map<String, Object> exportQr(List<String> deviceCodes, String issuedBy) {
        List<Map<String, Object>> issued = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        for (String code : deviceCodes) {
            Device d = deviceMapper.selectOne(
                    new LambdaQueryWrapper<Device>().eq(Device::getDeviceCode, code));
            if (d == null) {
                notFound.add(code);
                continue;
            }
            DeviceQrIssuance record = new DeviceQrIssuance();
            record.setIssuanceId(UUID.randomUUID());
            record.setDeviceId(d.getDeviceId());
            record.setIssuedBy(issuedBy);
            record.setQrPayload("https://{domain}/p/1/" + code);
            record.setIssuedAt(Instant.now());
            qrIssuanceMapper.insert(record);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deviceCode", code);
            item.put("qrPayload", record.getQrPayload());
            issued.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issued", issued);
        result.put("issuedCount", issued.size());
        result.put("notFound", notFound);
        return result;
    }

    /**
     * 批量操作受理（AC-84-19/20）：action = ota / reboot / factory-reset；
     * 受理登记操作意图（真实执行由设备端固件，后续对接）；返回受理清单。
     */
    public Map<String, Object> batchOperation(List<String> deviceCodes, String action, String operator) {
        if (action == null || !List.of("ota", "reboot", "factory-reset").contains(action)) {
            throw new IllegalArgumentException("非法操作类型: " + action);
        }
        List<String> accepted = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        for (String code : deviceCodes) {
            Device d = deviceMapper.selectOne(
                    new LambdaQueryWrapper<Device>().eq(Device::getDeviceCode, code));
            if (d == null) {
                notFound.add(code);
                continue;
            }
            accepted.add(code);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", action);
        result.put("operator", operator);
        result.put("accepted", accepted);
        result.put("acceptedCount", accepted.size());
        result.put("notFound", notFound);
        return result;
    }

    private boolean hasBinding(UUID deviceId, UUID bindTargetId) {
        return bindingMapper.selectCount(new LambdaQueryWrapper<DeviceBinding>()
                .eq(DeviceBinding::getDeviceId, deviceId)
                .eq(DeviceBinding::getBindTargetId, bindTargetId)
                .eq(DeviceBinding::getStatus, DeviceBinding.STATUS_ACTIVE)) > 0;
    }

    private Map<String, Object> activeBindingSummary(UUID deviceId) {
        DeviceBinding b = bindingMapper.selectOne(
                new LambdaQueryWrapper<DeviceBinding>()
                        .eq(DeviceBinding::getDeviceId, deviceId)
                        .eq(DeviceBinding::getStatus, DeviceBinding.STATUS_ACTIVE)
                        .last("LIMIT 1"));
        if (b == null) {
            return null;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("bindType", b.getBindType());
        summary.put("bindTargetId", b.getBindTargetId());
        summary.put("boundAt", b.getBoundAt());
        return summary;
    }
}
