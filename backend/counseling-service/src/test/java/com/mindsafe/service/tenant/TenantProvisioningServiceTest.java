package com.mindsafe.service.tenant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.School;
import com.mindsafe.domain.entity.Tenant;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.SchoolMapper;
import com.mindsafe.domain.mapper.TenantMapper;
import com.mindsafe.domain.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantProvisioningServiceTest {

    @Mock private TenantMapper tenantMapper;
    @Mock private SchoolMapper schoolMapper;
    @Mock private UserMapper userMapper;
    @Mock private PasswordEncoder passwordEncoder;

    private TenantProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new TenantProvisioningService(tenantMapper, schoolMapper, userMapper, passwordEncoder);
    }

    @Test
    void 开通租户创建三个实体() {
        when(tenantMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(passwordEncoder.encode("Temp123")).thenReturn("$2a$encoded");
        when(tenantMapper.insert(any(Tenant.class))).thenReturn(1);
        when(schoolMapper.insert(any(School.class))).thenReturn(1);
        when(userMapper.insert(any(User.class))).thenReturn(1);

        TenantProvisioningService.ProvisionResult result =
                service.provisionTenant("school-hz-001", "杭州实验小学", "13800138000", "张老师", "Temp123");

        assertNotNull(result.tenantId());
        assertNotNull(result.schoolId());
        assertNotNull(result.adminUserId());
        verify(tenantMapper).insert(any(Tenant.class));
        verify(schoolMapper).insert(any(School.class));
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void 重复租户编码抛异常() {
        Tenant existing = new Tenant();
        existing.setTenantCode("dup-code");
        when(tenantMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        assertThrows(IllegalArgumentException.class,
                () -> service.provisionTenant("dup-code", "重复学校", "138", "admin", "pass"));
        verify(tenantMapper, never()).insert(any(Tenant.class));
    }

    @Test
    void 暂停租户更新状态() {
        UUID tenantId = UUID.randomUUID();
        Tenant tenant = new Tenant();
        tenant.setTenantId(tenantId);
        tenant.setStatus("active");
        when(tenantMapper.selectById(tenantId)).thenReturn(tenant);
        when(tenantMapper.updateById(any(Tenant.class))).thenReturn(1);

        service.suspendTenant(tenantId, "违规");

        assertEquals("suspended", tenant.getStatus());
        verify(tenantMapper).updateById(tenant);
    }

    @Test
    void 暂停不存在的租户抛异常() {
        when(tenantMapper.selectById(any(UUID.class))).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.suspendTenant(UUID.randomUUID(), "test"));
    }

    @Test
    void 恢复租户状态变为active() {
        UUID tenantId = UUID.randomUUID();
        Tenant tenant = new Tenant();
        tenant.setTenantId(tenantId);
        tenant.setStatus("suspended");
        when(tenantMapper.selectById(tenantId)).thenReturn(tenant);
        when(tenantMapper.updateById(any(Tenant.class))).thenReturn(1);

        service.resumeTenant(tenantId);

        assertEquals("active", tenant.getStatus());
    }

    @Test
    void 健康检查返回完整信息() {
        UUID tenantId = UUID.randomUUID();
        Tenant tenant = new Tenant();
        tenant.setTenantId(tenantId);
        tenant.setStatus("active");
        tenant.setTenantName("测试学校");
        when(tenantMapper.selectById(tenantId)).thenReturn(tenant);
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L, 50L);
        when(schoolMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        Map<String, Object> health = service.healthCheck(tenantId);

        assertEquals("active", health.get("status"));
        assertEquals(true, health.get("healthy"));
        assertEquals(true, health.get("hasAdmin"));
        assertEquals(true, health.get("hasSchool"));
    }

    @Test
    void 健康检查租户不存在返回NOT_FOUND() {
        when(tenantMapper.selectById(any(UUID.class))).thenReturn(null);
        Map<String, Object> health = service.healthCheck(UUID.randomUUID());
        assertEquals("NOT_FOUND", health.get("status"));
    }

    @Test
    void 管理员为空时不健康() {
        UUID tenantId = UUID.randomUUID();
        Tenant tenant = new Tenant();
        tenant.setTenantId(tenantId);
        tenant.setStatus("active");
        tenant.setTenantName("无管理员学校");
        when(tenantMapper.selectById(tenantId)).thenReturn(tenant);
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L, 10L);
        when(schoolMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        Map<String, Object> health = service.healthCheck(tenantId);
        assertEquals(false, health.get("healthy"));
        assertEquals(false, health.get("hasAdmin"));
    }
}
