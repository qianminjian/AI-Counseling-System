package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/**
 * 平台管理员账号实体（对应 tenant_template.platform_admin，V35）
 * <p>
 * M6 平台基础：独立于租户 users 表的平台账号体系（DEC-007：独立表 + 独立登录
 * 端点 + PLATFORM_ token 前缀）。设计见 doing/83 后台管理端 §6.8。
 */
@TableName(value = "platform_admin", schema = TenantSchema.TENANT_TEMPLATE)
public class PlatformAdmin {

    /** 角色：超级管理员（全部权限） */
    public static final String ROLE_SUPER_ADMIN = "super_admin";

    /** 角色：运维管理员（监控/降级/告警） */
    public static final String ROLE_OPS_ADMIN = "ops_admin";

    /** 角色：财务管理员（计量/报表） */
    public static final String ROLE_FINANCE_ADMIN = "finance_admin";

    /** 角色：审计（只读） */
    public static final String ROLE_AUDIT = "audit";

    /** 状态：启用 */
    public static final String STATUS_ACTIVE = "active";

    /** 状态：禁用（拒绝登录） */
    public static final String STATUS_DISABLED = "disabled";

    @TableId(value = "admin_id", type = IdType.INPUT)
    private UUID adminId;

    /** 登录名（唯一） */
    private String username;

    /** BCrypt 密码哈希 */
    private String passwordHash;

    /** super_admin/ops_admin/finance_admin/audit */
    private String role;

    /** 显示名 */
    private String displayName;

    /** active/disabled */
    private String status;

    /** 创建时间 */
    private Instant createdAt;

    /** 最近登录时间 */
    private Instant lastLoginAt;

    public UUID getAdminId() {
        return adminId;
    }

    public void setAdminId(UUID adminId) {
        this.adminId = adminId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }
}
