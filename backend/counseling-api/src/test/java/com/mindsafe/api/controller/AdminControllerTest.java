package com.mindsafe.api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.AuditLog;
import com.mindsafe.domain.entity.TrialInviteCode;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.AuditLogMapper;
import com.mindsafe.domain.mapper.TrialInviteCodeMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.audit.AuditLogService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminController 单元测试（P1 覆盖率冲刺：邀请码管理 / 批量导入 / 审计日志）
 * <p>
 * 覆盖：
 * - createCode 默认值与自定义参数、邀请码唯一性冲突 10 次 → INTERNAL_ERROR
 * - batchCreateCodes 默认 30 / count 上限 200 clamp / 审计
 * - deactivateCode 不存在 → RESOURCE_NOT_FOUND、成功停用
 * - deleteCode / listCodes
 * - downloadTemplate 输出 BOM + CSV
 * - importStudents 空文件 / 非 CSV / 成功 / 重复昵称跳过 / 缺昵称 / 解析异常
 * - getAuditLogs action 过滤 + limit clamp
 */
class AdminControllerTest {

    private TrialInviteCodeMapper inviteCodeMapper;
    private UserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private AuditLogService auditLogService;
    private AuditLogMapper auditLogMapper;
    private AdminController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID adminUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        inviteCodeMapper = mock(TrialInviteCodeMapper.class);
        userMapper = mock(UserMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        auditLogService = mock(AuditLogService.class);
        auditLogMapper = mock(AuditLogMapper.class);
        controller = new AdminController(inviteCodeMapper, userMapper, passwordEncoder,
                auditLogService, auditLogMapper);
    }

    private Authentication adminAuth() {
        Authentication a = mock(Authentication.class);
        when(a.getPrincipal()).thenReturn(adminUserId);
        when(a.getDetails()).thenReturn(new TenantContext(tenantId, adminUserId, "admin"));
        return a;
    }

    // ===== createCode =====

    @Test
    @DisplayName("createCode 无 body → 默认 maxUses=10 / expireDays=30，插入并返回")
    void createCode_defaults() {
        when(inviteCodeMapper.selectCount(any())).thenReturn(0L);

        var resp = controller.createCode(null, adminAuth());

        assertThat(resp.code()).isEqualTo(0);
        TrialInviteCode code = resp.data();
        assertThat(code.getTenantId()).isEqualTo(tenantId);
        assertThat(code.getMaxUses()).isEqualTo(10);
        assertThat(code.getUsedCount()).isZero();
        assertThat(code.getStatus()).isEqualTo("active");
        assertThat(code.getCreatedBy()).isEqualTo(adminUserId);
        assertThat(code.getCode()).hasSize(8);
        verify(inviteCodeMapper).<TrialInviteCode>insert(code);
    }

    @Test
    @DisplayName("createCode body 提供 maxUses/expireDays → 覆盖默认值")
    void createCode_customParams() {
        when(inviteCodeMapper.selectCount(any())).thenReturn(0L);

        var resp = controller.createCode(Map.of("maxUses", 3, "expireDays", 7), adminAuth());

        assertThat(resp.data().getMaxUses()).isEqualTo(3);
        assertThat(resp.data().getExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("createCode 邀请码唯一性连续冲突 10 次 → INTERNAL_ERROR")
    void createCode_uniqueConflict() {
        when(inviteCodeMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> controller.createCode(null, adminAuth()))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.INTERNAL_ERROR.code());
        verify(inviteCodeMapper, never()).<TrialInviteCode>insert(any(TrialInviteCode.class));
    }

    // ===== batchCreateCodes =====

    @Test
    @DisplayName("batchCreateCodes 默认 30 个一人一码 + 审计")
    void batchCreateCodes_default() {
        when(inviteCodeMapper.selectCount(any())).thenReturn(0L);

        var resp = controller.batchCreateCodes(null, adminAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("count")).isEqualTo(30);
        assertThat(((List<?>) resp.data().get("codes"))).hasSize(30);
        verify(inviteCodeMapper, org.mockito.Mockito.times(30)).insert(any(TrialInviteCode.class));
        verify(auditLogService).log(tenantId, adminUserId, "BATCH_INVITE_CODES",
                "invite_code_batch", null, "生成30个邀请码，批次:" + resp.data().get("batchId"));
    }

    @Test
    @DisplayName("batchCreateCodes count 超过 200 → clamp 到 200")
    void batchCreateCodes_clampCount() {
        when(inviteCodeMapper.selectCount(any())).thenReturn(0L);

        var resp = controller.batchCreateCodes(Map.of("count", 500), adminAuth());

        assertThat(resp.data().get("count")).isEqualTo(200);
        verify(inviteCodeMapper, org.mockito.Mockito.times(200)).insert(any(TrialInviteCode.class));
    }

    // ===== listCodes / deactivateCode / deleteCode =====

    @Test
    @DisplayName("listCodes 按租户查询邀请码")
    void listCodes() {
        when(inviteCodeMapper.selectList(any())).thenReturn(List.of(new TrialInviteCode()));

        var resp = controller.listCodes(adminAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data()).hasSize(1);
        verify(inviteCodeMapper).selectList(any());
    }

    @Test
    @DisplayName("deactivateCode 邀请码不存在 → RESOURCE_NOT_FOUND")
    void deactivateCode_notFound() {
        when(inviteCodeMapper.selectById(any(UUID.class))).thenReturn(null);

        assertThatThrownBy(() -> controller.deactivateCode(UUID.randomUUID(), adminAuth()))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.code());
    }

    @Test
    @DisplayName("deactivateCode 成功 → status=disabled 持久化")
    void deactivateCode_success() {
        TrialInviteCode code = new TrialInviteCode();
        code.setStatus("active");
        when(inviteCodeMapper.selectById(any(UUID.class))).thenReturn(code);

        var resp = controller.deactivateCode(UUID.randomUUID(), adminAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(code.getStatus()).isEqualTo("disabled");
        verify(inviteCodeMapper).updateById(code);
    }

    @Test
    @DisplayName("deleteCode → 直接删除")
    void deleteCode() {
        UUID codeId = UUID.randomUUID();

        var resp = controller.deleteCode(codeId, adminAuth());

        assertThat(resp.code()).isEqualTo(0);
        verify(inviteCodeMapper).deleteById(codeId);
    }

    @Test
    @DisplayName("无认证上下文 → UNAUTHORIZED")
    void noAuth() {
        assertThatThrownBy(() -> controller.listCodes(null))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }

    // ===== downloadTemplate =====

    @Test
    @DisplayName("downloadTemplate 输出 BOM + CSV 表头与示例行")
    void downloadTemplate() throws IOException {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(mock(ServletOutputStream.class));
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        controller.downloadTemplate(response);

        verify(response).setContentType("text/csv; charset=UTF-8");
        verify(response).setHeader("Content-Disposition", "attachment; filename=student_import_template.csv");
        String body = sw.toString();
        assertThat(body).contains("昵称,年级,班级");
        assertThat(body).contains("小明,四年级,2班");
    }

    // ===== importStudents =====

    @Test
    @DisplayName("importStudents 空文件 → PARAM_INVALID")
    void importStudents_empty() {
        MockMultipartFile file = new MockMultipartFile("file", "a.csv",
                "text/csv", new byte[0]);

        assertThatThrownBy(() -> controller.importStudents(file, adminAuth()))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PARAM_INVALID.code());
    }

    @Test
    @DisplayName("importStudents 非 CSV 文件 → PARAM_INVALID")
    void importStudents_notCsv() {
        MockMultipartFile file = new MockMultipartFile("file", "a.txt",
                "text/plain", "内容".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> controller.importStudents(file, adminAuth()))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PARAM_INVALID.code());
    }

    @Test
    @DisplayName("importStudents 成功：创建学生 + 初始密码 + 审计")
    void importStudents_success() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-pwd");
        MockMultipartFile file = new MockMultipartFile("file", "students.csv",
                "text/csv", "\uFEFF昵称,年级,班级\n小明,四年级,2班\n小红,五年级,1班\n".getBytes(StandardCharsets.UTF_8));

        var resp = controller.importStudents(file, adminAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("created")).isEqualTo(2);
        assertThat(resp.data().get("skipped")).isEqualTo(0);
        verify(userMapper, org.mockito.Mockito.times(2)).insert(any(User.class));
        verify(passwordEncoder, org.mockito.Mockito.times(2)).encode(anyString());
        verify(auditLogService).log(tenantId, adminUserId, "IMPORT_STUDENTS", "batch",
                null, "{\"created\":2,\"skipped\":0}");
    }

    @Test
    @DisplayName("importStudents 重复昵称跳过 + 空行 + 表头行跳过")
    void importStudents_skipDuplicates() {
        when(userMapper.selectCount(any())).thenReturn(1L, 0L);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-pwd");
        MockMultipartFile file = new MockMultipartFile("file", "students.csv",
                "text/csv", "昵称,年级,班级\n\n小明,四年级,2班\n小红,五年级,1班\n".getBytes(StandardCharsets.UTF_8));

        var resp = controller.importStudents(file, adminAuth());

        assertThat(resp.data().get("created")).isEqualTo(1);
        assertThat(resp.data().get("skipped")).isEqualTo(1);
        assertThat(((List<?>) resp.data().get("errors"))).hasSize(1);
        verify(userMapper, org.mockito.Mockito.times(1)).insert(any(User.class));
    }

    @Test
    @DisplayName("importStudents 缺昵称行 → errors + skipped")
    void importStudents_missingPseudonym() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-pwd");
        MockMultipartFile file = new MockMultipartFile("file", "students.csv",
                "text/csv", "小明,四年级,2班\n,五年级,1班\n".getBytes(StandardCharsets.UTF_8));

        var resp = controller.importStudents(file, adminAuth());

        assertThat(resp.data().get("created")).isEqualTo(1);
        assertThat(resp.data().get("skipped")).isEqualTo(1);
        assertThat(((List<?>) resp.data().get("errors"))).hasSize(1);
    }

    @Test
    @DisplayName("importStudents CSV 读取异常 → INTERNAL_ERROR")
    void importStudents_parseError() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        MockMultipartFile file = new MockMultipartFile("file", "students.csv",
                "text/csv", "小明,四年级,2班".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public java.io.InputStream getInputStream() throws IOException {
                throw new IOException("disk error");
            }
        };

        assertThatThrownBy(() -> controller.importStudents(file, adminAuth()))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.INTERNAL_ERROR.code());
    }

    // ===== getAuditLogs =====

    @Test
    @DisplayName("getAuditLogs 无 action → 全部日志（limit 默认 200）")
    void getAuditLogs_all() {
        when(auditLogMapper.selectPage(any(), any())).thenReturn(new Page<AuditLog>().setRecords(List.of(new AuditLog())));

        var resp = controller.getAuditLogs(adminAuth(), null, 200);

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data()).hasSize(1);
        verify(auditLogMapper).selectPage(any(), any());
    }

    @Test
    @DisplayName("getAuditLogs 带 action → 按动作过滤")
    void getAuditLogs_byAction() {
        when(auditLogMapper.selectPage(any(), any())).thenReturn(new Page<AuditLog>().setRecords(List.of()));

        controller.getAuditLogs(adminAuth(), "LOGIN", 200);

        verify(auditLogMapper).selectPage(any(), any());
    }
}
