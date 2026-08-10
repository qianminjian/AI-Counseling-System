package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/**
 * 无屏终端设备档案（对应 tenant_template.device，V39）
 * <p>
 * CFG-001：toB 第四端（无屏交互终端）设备全生命周期——扫码配网→回连注册→
 * 验证码绑定→日常管理。设计见 doing/84 §六.1。
 */
@TableName(value = "device", schema = "tenant_template")
public class Device {

    /** 状态：未激活（出厂） */
    public static final String STATUS_UNACTIVATED = "UNACTIVATED";

    /** 状态：配网中（Soft-AP） */
    public static final String STATUS_PROVISIONING = "PROVISIONING";

    /** 状态：已联网待绑定（回连检查通过） */
    public static final String STATUS_ONLINE_UNBOUND = "ONLINE_UNBOUND";

    /** 状态：已绑定运行中 */
    public static final String STATUS_ONLINE_BOUND = "ONLINE_BOUND";

    /** 状态：离线 */
    public static final String STATUS_OFFLINE = "OFFLINE";

    /** 状态：已注销 */
    public static final String STATUS_RETIRED = "RETIRED";

    /** 心跳超时阈值（秒）：90s 无心跳判离线 */
    public static final long HEARTBEAT_TIMEOUT_SECONDS = 90L;

    @TableId(value = "device_id", type = IdType.INPUT)
    private UUID deviceId;

    /** 短码（机身/包装二维码载体，SN 派生 + Luhn 校验位，11 位） */
    private String deviceCode;

    /** 出厂序列号（不对外，仅内部） */
    private String sn;

    /** 形态描述符：plush/desk_toy/pendant */
    private String deviceType;

    /** 当前固件版本（状态上报更新） */
    private String firmwareVersion;

    /** UNACTIVATED/PROVISIONING/ONLINE_UNBOUND/ONLINE_BOUND/OFFLINE/RETIRED */
    private String status;

    /** 配网写入的服务器地址（toB 校内地址预置可改） */
    private String serverUrl;

    /** 最近在线时间（心跳更新） */
    private Instant lastOnlineAt;

    /** 最近离线时间 */
    private Instant lastOfflineAt;

    private Instant createdAt;

    /** 设备 HMAC 密钥（P0-1，V44） */
    private String deviceSecret;

    /** 设备 JWT token（P0-1，reportOnline 签发） */
    private String deviceToken;

    private Instant updatedAt;

    public UUID getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public void setDeviceCode(String deviceCode) {
        this.deviceCode = deviceCode;
    }

    public String getSn() {
        return sn;
    }

    public void setSn(String sn) {
        this.sn = sn;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    public void setFirmwareVersion(String firmwareVersion) {
        this.firmwareVersion = firmwareVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public Instant getLastOnlineAt() {
        return lastOnlineAt;
    }

    public void setLastOnlineAt(Instant lastOnlineAt) {
        this.lastOnlineAt = lastOnlineAt;
    }

    public Instant getLastOfflineAt() {
        return lastOfflineAt;
    }

    public void setLastOfflineAt(Instant lastOfflineAt) {
        this.lastOfflineAt = lastOfflineAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getDeviceSecret() {
        return deviceSecret;
    }

    public void setDeviceSecret(String deviceSecret) {
        this.deviceSecret = deviceSecret;
    }

    public String getDeviceToken() {
        return deviceToken;
    }

    public void setDeviceToken(String deviceToken) {
        this.deviceToken = deviceToken;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
