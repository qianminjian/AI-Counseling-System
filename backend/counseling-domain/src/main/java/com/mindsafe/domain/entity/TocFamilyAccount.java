package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/**
 * toC 家庭账号（对应 tenant_template.toc_family_accounts，V42）
 * <p>
 * TOC-001：手机号验证码注册/登录，独立于校园账号体系（doing/85 §四）。
 */
@TableName(value = "toc_family_accounts", schema = TenantSchema.TENANT_TEMPLATE)
public class TocFamilyAccount {

    /** 状态：正常 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 状态：禁用（隐私控制关闭） */
    public static final String STATUS_DISABLED = "DISABLED";

    @TableId(type = IdType.INPUT)
    private UUID familyAccountId;

    /** 手机号（登录标识，唯一） */
    private String phone;

    /** ACTIVE/DISABLED */
    private String status;

    private Instant createdAt;

    private Instant updatedAt;

    public UUID getFamilyAccountId() {
        return familyAccountId;
    }

    public void setFamilyAccountId(UUID familyAccountId) {
        this.familyAccountId = familyAccountId;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
