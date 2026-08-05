package com.mindsafe.service.tenant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.School;
import com.mindsafe.domain.entity.Tenant;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.SchoolMapper;
import com.mindsafe.domain.mapper.TenantMapper;
import com.mindsafe.domain.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 租户开通与管理服务（BIZ-001：多租户生产化）
 * <p>
 * 功能：
 * 1. 一键开通：创建 tenant + school + admin 用户（事务原子性）
 * 2. 租户配额：学生数上限、会话数上限
 * 3. 租户状态管理：active / suspended / archived
 * 4. 健康检查：验证租户数据完整性
 */
@Service
public class TenantProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(TenantProvisioningService.class);

    /** 默认配额 */
    private static final int DEFAULT_MAX_STUDENTS = 500;
    private static final int DEFAULT_MAX_SESSIONS_PER_DAY = 200;

    private final TenantMapper tenantMapper;
    private final SchoolMapper schoolMapper;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public TenantProvisioningService(TenantMapper tenantMapper, SchoolMapper schoolMapper,
                                     UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.tenantMapper = tenantMapper;
        this.schoolMapper = schoolMapper;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 一键开通租户（事务：tenant + school + admin 用户）
     *
     * @param tenantCode  租户编码（唯一，如 school-hz-001）
     * @param tenantName  学校名称
     * @param adminPhone  管理员手机号
     * @param adminName   管理员姓名
     * @param tempPassword 临时密码（首次登录强制修改）
     * @return 开通结果
     */
    @Transactional
    public ProvisionResult provisionTenant(String tenantCode, String tenantName,
                                           String adminPhone, String adminName,
                                           String tempPassword) {
        // 1. 校验唯一性
        Tenant existing = tenantMapper.selectOne(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getTenantCode, tenantCode));
        if (existing != null) {
            throw new IllegalArgumentException("租户编码已存在: " + tenantCode);
        }

        UUID tenantId = UUID.randomUUID();

        // 2. 创建租户
        Tenant tenant = new Tenant();
        tenant.setTenantId(tenantId);
        tenant.setTenantCode(tenantCode);
        tenant.setTenantName(tenantName);
        tenant.setDataRegion("cn-east");
        tenant.setStatus(Tenant.STATUS_ACTIVE);
        tenant.setCreatedAt(Instant.now());
        tenant.setUpdatedAt(Instant.now());
        tenantMapper.insert(tenant);

        // 3. 创建学校
        School school = new School();
        school.setSchoolId(UUID.randomUUID());
        school.setTenantId(tenantId);
        school.setSchoolCode(tenantCode);
        school.setSchoolName(tenantName);
        school.setEduStage("primary");
        school.setStatus(School.STATUS_ACTIVE);
        school.setCreatedAt(Instant.now());
        school.setUpdatedAt(Instant.now());
        schoolMapper.insert(school);

        // 4. 创建管理员用户
        User admin = new User();
        admin.setUserId(UUID.randomUUID());
        admin.setTenantId(tenantId);
        admin.setSchoolId(school.getSchoolId());
        admin.setPseudonym(adminName != null ? adminName : "管理员");
        admin.setUserType("admin");
        admin.setPasswordHash(passwordEncoder.encode(tempPassword));
        admin.setMustChangePassword(true);
        admin.setStatus(User.STATUS_ACTIVE);
        admin.setCreatedAt(Instant.now());
        admin.setUpdatedAt(Instant.now());
        userMapper.insert(admin);

        log.info("租户开通完成: tenantId={}, code={}, school={}, admin={}",
                tenantId, tenantCode, tenantName, adminPhone);

        return new ProvisionResult(tenantId, school.getSchoolId(), admin.getUserId());
    }

    /**
     * 暂停租户（数据保留，禁止登录）
     */
    public void suspendTenant(UUID tenantId, String reason) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) throw new IllegalArgumentException("租户不存在");
        tenant.setStatus(Tenant.STATUS_SUSPENDED);
        tenant.setUpdatedAt(Instant.now());
        tenantMapper.updateById(tenant);
        log.warn("租户已暂停: tenantId={}, reason={}", tenantId, reason);
    }

    /**
     * 恢复租户
     */
    public void resumeTenant(UUID tenantId) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) throw new IllegalArgumentException("租户不存在");
        tenant.setStatus(Tenant.STATUS_ACTIVE);
        tenant.setUpdatedAt(Instant.now());
        tenantMapper.updateById(tenant);
        log.info("租户已恢复: tenantId={}", tenantId);
    }

    /**
     * 租户健康检查
     */
    public Map<String, Object> healthCheck(UUID tenantId) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            return Map.of("status", "NOT_FOUND");
        }

        long adminCount = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getTenantId, tenantId)
                .eq(User::getUserType, "admin"));
        long studentCount = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getTenantId, tenantId)
                .eq(User::getUserType, User.USER_TYPE_STUDENT));
        long schoolCount = schoolMapper.selectCount(new LambdaQueryWrapper<School>()
                .eq(School::getTenantId, tenantId));

        Map<String, Object> health = new java.util.LinkedHashMap<>();
        health.put("tenantId", tenantId);
        health.put("status", tenant.getStatus());
        health.put("tenantName", tenant.getTenantName());
        health.put("adminCount", adminCount);
        health.put("studentCount", studentCount);
        health.put("schoolCount", schoolCount);
        health.put("hasAdmin", adminCount > 0);
        health.put("hasSchool", schoolCount > 0);
        health.put("healthy", adminCount > 0 && schoolCount > 0 && Tenant.STATUS_ACTIVE.equals(tenant.getStatus()));
        return health;
    }

    /**
     * 查询所有租户列表
     */
    public List<Tenant> listTenants() {
        return tenantMapper.selectList(new LambdaQueryWrapper<Tenant>()
                .isNull(Tenant::getDeletedAt)
                .orderByDesc(Tenant::getCreatedAt));
    }

    /** 开通结果 */
    public record ProvisionResult(UUID tenantId, UUID schoolId, UUID adminUserId) {
    }
}
