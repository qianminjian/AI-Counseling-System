package com.mindsafe.service.admin;

import com.mindsafe.domain.entity.User;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
}
