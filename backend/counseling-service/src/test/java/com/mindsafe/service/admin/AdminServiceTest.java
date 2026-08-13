package com.mindsafe.service.admin;

import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.TrialInviteCode;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.entity.AuditLog;
import com.mindsafe.domain.mapper.AuditLogMapper;
import com.mindsafe.domain.mapper.TrialInviteCodeMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminService 学生批量导入单测（P1-5：初始密码分发 / 引号感知 CSV / classCode 预校验）。
 * <p>
 * 契约：
 * - 成功行 → userMapper.insert 且 mustChangePassword=true；密码随 ImportResult.initPasswords 返回（\d{6}）
 * - 引号包裹字段（含逗号）正确解析，"" 转义为字面引号
 * - classCode 缺失/超长 → 跳过并记录错误，不落库
 * - 同租户重复昵称 → 跳过
 */
@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock private TrialInviteCodeMapper inviteCodeMapper;
    @Mock private UserMapper userMapper;
    @Mock private AuditLogMapper auditLogMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditLogService auditLogService;

    private AdminService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID adminUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AdminService(inviteCodeMapper, userMapper, auditLogMapper, passwordEncoder, auditLogService);
        // lenient：parseCsvLine 纯静态解析用例不消费 mock，strict 模式需显式声明（同 TeacherStatsPerformanceTest 先例）
        lenient().when(passwordEncoder.encode(any())).thenAnswer(inv -> "ENC:" + inv.getArgument(0));
        lenient().when(userMapper.selectCount(any())).thenReturn(0L);
    }

    private InputStream csv(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private List<User> insertedUsers() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("导入成功：创建学生 + 初始密码随结果返回（6 位数字）+ 强制改密 + 审计")
    void import_success_distributesPasswords() {
        AdminService.ImportResult r = service.importStudents(tenantId, adminUserId,
                csv("\uFEFF昵称,年级,班级\n小明,四年级,2班\n小红,五年级,1班\n"));

        assertThat(r.created()).isEqualTo(2);
        assertThat(r.skipped()).isZero();
        assertThat(r.errors()).isEmpty();
        assertThat(r.initPasswords()).hasSize(2).allSatisfy(p -> assertThat(p).matches("\\d{6}"));

        List<User> users = insertedUsers();
        assertThat(users).hasSize(2);
        assertThat(users.get(0).getPseudonym()).isEqualTo("小明");
        assertThat(users.get(0).getClassCode()).isEqualTo("2班");
        assertThat(users.get(0).getUserType()).isEqualTo(User.USER_TYPE_STUDENT);
        assertThat(users.get(0).getMustChangePassword()).isTrue();
        // 密码列与落库哈希同源（明文 = 返回密码，哈希 = ENC:明文）
        assertThat(users.get(0).getPasswordHash()).isEqualTo("ENC:" + r.initPasswords().get(0));

        verify(auditLogService).log(eq(tenantId), eq(adminUserId), eq("IMPORT_STUDENTS"), eq("batch"),
                eq(null), org.mockito.ArgumentMatchers.contains("\"created\":2"));
    }

    @Test
    @DisplayName("引号昵称含逗号：正确解析为单字段")
    void import_quotedPseudonymWithComma() {
        AdminService.ImportResult r = service.importStudents(tenantId, adminUserId,
                csv("昵称,年级,班级\n\"小明,同学\",四年级,2班\n"));

        assertThat(r.created()).isEqualTo(1);
        assertThat(r.skipped()).isZero();
        List<User> users = insertedUsers();
        assertThat(users.get(0).getPseudonym()).isEqualTo("小明,同学");
    }

    @Test
    @DisplayName("parseCsvLine：双引号转义为字面引号")
    void parseCsvLine_escapedQuote() {
        List<String> fields = AdminService.parseCsvLine("\"小明\"\"同学\",四年级,2班");
        assertThat(fields).containsExactly("小明\"同学", "四年级", "2班");
    }

    @Test
    @DisplayName("parseCsvLine：普通行与空字段")
    void parseCsvLine_plain() {
        assertThat(AdminService.parseCsvLine("小明,四年级,2班")).containsExactly("小明", "四年级", "2班");
        assertThat(AdminService.parseCsvLine("小明,,2班")).containsExactly("小明", "", "2班");
        assertThat(AdminService.parseCsvLine("")).containsExactly("");
    }

    @Test
    @DisplayName("classCode 缺失：跳过并报错，不落库")
    void import_missingClassCode_skipped() {
        AdminService.ImportResult r = service.importStudents(tenantId, adminUserId,
                csv("昵称,年级,班级\n小明,四年级,\n小红,五年级,1班\n"));

        assertThat(r.created()).isEqualTo(1);
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(r.errors()).containsExactly("第2行：\"小明\" 缺少班级，跳过");
        assertThat(r.initPasswords()).hasSize(1);
        List<User> users = insertedUsers();
        assertThat(users).singleElement().extracting(User::getPseudonym).isEqualTo("小红");
    }

    @Test
    @DisplayName("classCode 超长（>32）：跳过并报错")
    void import_classCodeTooLong_skipped() {
        String longClass = "X".repeat(33);
        AdminService.ImportResult r = service.importStudents(tenantId, adminUserId,
                csv("昵称,年级,班级\n小明,四年级," + longClass + "\n"));

        assertThat(r.created()).isZero();
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(r.errors()).hasSize(1).first().asString().contains("班级字段超长");
    }

    @Test
    @DisplayName("同租户重复昵称：跳过")
    void import_duplicatePseudonym_skipped() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        AdminService.ImportResult r = service.importStudents(tenantId, adminUserId,
                csv("昵称,年级,班级\n小明,四年级,2班\n"));

        assertThat(r.created()).isZero();
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(r.errors()).first().asString().contains("已存在");
    }

    @Test
    @DisplayName("BOM 表头跳过：首行昵称表头不落库")
    void import_headerSkipped() {
        AdminService.ImportResult r = service.importStudents(tenantId, adminUserId,
                csv("昵称,年级,班级\n小明,四年级,2班\n"));

        assertThat(r.created()).isEqualTo(1);
        assertThat(r.skipped()).isZero();
        List<User> users = insertedUsers();
        assertThat(users).singleElement().extracting(User::getPseudonym).isEqualTo("小明");
    }

    @Test
    @DisplayName("createInviteCode：生成唯一码 + 默认状态/额度并落库")
    void createInviteCode() {
        when(inviteCodeMapper.selectCount(any())).thenReturn(0L);

        TrialInviteCode code = service.createInviteCode(tenantId, adminUserId, 5, 7);

        assertThat(code.getTenantId()).isEqualTo(tenantId);
        assertThat(code.getMaxUses()).isEqualTo(5);
        assertThat(code.getUsedCount()).isZero();
        assertThat(code.getStatus()).isEqualTo(TrialInviteCode.STATUS_ACTIVE);
        assertThat(code.getExpiresAt()).isAfter(java.time.Instant.now());
        assertThat(code.getCode()).hasSize(8);
        verify(inviteCodeMapper).insert(code);
    }

    @Test
    @DisplayName("createInviteCode：唯一码冲突 10 次后抛 INTERNAL_ERROR")
    void createInviteCode_collision() {
        when(inviteCodeMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.createInviteCode(tenantId, adminUserId, 5, 7))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("batchCreateCodes：批量一人一码 + 批次审计")
    void batchCreateCodes() {
        when(inviteCodeMapper.selectCount(any())).thenReturn(0L);

        AdminService.BatchResult result = service.batchCreateCodes(tenantId, adminUserId, 3, 7);

        assertThat(result.count()).isEqualTo(3);
        assertThat(result.codes()).hasSize(3);
        assertThat(result.batchId()).startsWith("BATCH-");
        verify(inviteCodeMapper, times(3)).insert(org.mockito.ArgumentMatchers.any(TrialInviteCode.class));
        verify(auditLogService).log(eq(tenantId), eq(adminUserId), eq("BATCH_INVITE_CODES"),
                eq("invite_code_batch"), eq(null), org.mockito.ArgumentMatchers.contains("3个邀请码"));
    }

    @Test
    @DisplayName("listInviteCodes：按租户过滤倒序")
    void listInviteCodes() {
        when(inviteCodeMapper.selectList(any())).thenReturn(List.of(new TrialInviteCode()));

        List<TrialInviteCode> result = service.listInviteCodes(tenantId);

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("deactivateInviteCode：命中后置 disabled 更新")
    void deactivateInviteCode() {
        TrialInviteCode code = new TrialInviteCode();
        code.setCodeId(java.util.UUID.randomUUID());
        when(inviteCodeMapper.selectOne(any())).thenReturn(code);

        service.deactivateInviteCode(tenantId, code.getCodeId());

        assertThat(code.getStatus()).isEqualTo("disabled");
        verify(inviteCodeMapper).updateById(code);
    }

    @Test
    @DisplayName("deactivateInviteCode：不存在抛 RESOURCE_NOT_FOUND")
    void deactivateInviteCode_notFound() {
        when(inviteCodeMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.deactivateInviteCode(tenantId, java.util.UUID.randomUUID()))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("deleteInviteCode：按租户+ID 删除")
    void deleteInviteCode() {
        service.deleteInviteCode(tenantId, java.util.UUID.randomUUID());

        verify(inviteCodeMapper).delete(any());
    }

    @Test
    @DisplayName("getAuditLogs：带 action 过滤 + limit 截断")
    void getAuditLogs_withAction() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<AuditLog> page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 5, false);
        page.setRecords(List.of(new AuditLog()));
        when(auditLogMapper.selectPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(page);

        List<AuditLog> result = service.getAuditLogs(tenantId, "RESET_PASSWORD", 5);

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("getAuditLogs：无 action 查全部")
    void getAuditLogs_all() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<AuditLog> page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10, false);
        page.setRecords(List.of(new AuditLog(), new AuditLog()));
        when(auditLogMapper.selectPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(page);

        List<AuditLog> result = service.getAuditLogs(tenantId, "  ", 10);

        assertThat(result).hasSize(2);
    }
}
