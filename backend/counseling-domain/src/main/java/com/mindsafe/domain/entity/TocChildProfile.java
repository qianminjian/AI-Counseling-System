package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/**
 * toC 孩子档案（对应 tenant_template.toc_child_profiles，V42）
 * <p>
 * TOC-002：一账号多孩档案（昵称/年龄/性别/兴趣），按 family_account_id 数据隔离
 * （doing/85 §四/§五 toC-AC-2）。
 */
@TableName(value = "toc_child_profiles", schema = TenantSchema.TENANT_TEMPLATE)
public class TocChildProfile {

    @TableId(type = IdType.INPUT)
    private UUID profileId;

    /** 归属家庭账号（数据隔离键） */
    private UUID familyAccountId;

    /** 昵称 */
    private String nickname;

    /** 年龄 */
    private Integer age;

    /** MALE/FEMALE/UNSPECIFIED */
    private String gender;

    /** 兴趣（逗号分隔，成长报告算法输入） */
    private String interests;

    private Instant createdAt;

    private Instant updatedAt;

    public UUID getProfileId() {
        return profileId;
    }

    public void setProfileId(UUID profileId) {
        this.profileId = profileId;
    }

    public UUID getFamilyAccountId() {
        return familyAccountId;
    }

    public void setFamilyAccountId(UUID familyAccountId) {
        this.familyAccountId = familyAccountId;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getInterests() {
        return interests;
    }

    public void setInterests(String interests) {
        this.interests = interests;
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
