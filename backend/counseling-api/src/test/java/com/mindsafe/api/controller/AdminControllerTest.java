package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.AuditLog;
import com.mindsafe.domain.entity.TrialInviteCode;
import com.mindsafe.service.admin.AdminService;
import com.mindsafe.service.audit.AuditLogService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminController 单元测试（T4 批次B/C 改造版：SQL 下沉 AdminService，Controller 仅 HTTP 层职责）
 * <p>
 * 覆盖：
 * - createCode 默认值与自定义参数、唯一性冲突 10 次 → INTERNAL_ERROR（异常由 Service 抛出）
 * - batchCreateCodes 默认 30 / count 上限 200 clamp
 * - deactivateCode 不存在 → RESOURCE_NOT_FOUND、成功停用
 * - deleteCode / listCodes
 * - downloadTemplate 输出 BOM + CSV
 * - importStudents 空文件 / 非 CSV / 成功 / 重复昵称跳过 / 缺昵称 / 解析异常
 * - getAuditLogs action 过滤 + limit clamp
 * <p>
 * 域语义（唯一性冲突 / CSV 解析 / 去重 / 审计）由 AdminService 测试覆盖——本测试经 Service 接口验证 Controller 编排。
 */
class AdminControllerTest {

    private AdminService adminService;
    private AdminController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID adminUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adminService = mock(AdminService.class);
        controller = new AdminController(adminService);
    }

    private Authentication adminAuth() {
        Authentication a = mock(Authentication.class);
        when(a.getPrincipal()).thenReturn(adminUserId);
        when(a.getDetails()).thenReturn(new TenantContext(tenantId, adminUserId, "admin"));
        return a;
    }

    private TrialInviteCode inviteCode(int maxUses, int expireDays) {
        TrialInviteCode code = new TrialInviteCode();
        code.setCodeId(UUID.randomUUID());
        code.setTenantId(tenantId);
        code.setCode("ABCD1234");
        code.setMaxUses(maxUses);
        code.setUsedCount(0);
        code.setStatus("active");
        code.setCreatedBy(adminUserId);
        // 有效期由 Service 创建时填充（T4 下沉后 Controller 透传）
        code.setExpiresAt(java.time.Instant.now().plusSeconds(expireDays * 86400L));
        return code;
    }

    // ===== createCode =====

    @Test
    @DisplayName("createCode 无 body → 默认 maxUses=10 / expireDays=30，调用服务并返回")
    void createCode_defaults() {
        TrialInviteCode code = inviteCode(10, 30);
        when(adminService.createInviteCode(tenantId, adminUserId, 10, 30)).thenReturn(code);

        var resp = controller.createCode(null, adminAuth());

        assertThat(resp.code()).isEqualTo(0);
        TrialInviteCode returned = resp.data();
        assertThat(returned.getTenantId()).isEqualTo(tenantId);
        assertThat(returned.getMaxUses()).isEqualTo(10);
        assertThat(returned.getUsedCount()).isZero();
        assertThat(returned.getStatus()).isEqualTo("active");
        assertThat(returned.getCreatedBy()).isEqualTo(adminUserId);
        assertThat(returned.getCode()).hasSize(8);
        verify(adminService).createInviteCode(tenantId, adminUserId, 10, 30);
    }

    @Test
    @DisplayName("createCode body 提供 maxUses/expireDays → 覆盖默认值")
    void createCode_customParams() {
        TrialInviteCode code = inviteCode(3, 7);
        when(adminService.createInviteCode(tenantId, adminUserId, 3, 7)).thenReturn(code);

        var resp = controller.createCode(Map.of("maxUses", 3, "expireDays", 7), adminAuth());

        assertThat(resp.data().getMaxUses()).isEqualTo(3);
        assertThat(resp.data().getExpiresAt()).isNotNull();
        verify(adminService).createInviteCode(tenantId, adminUserId, 3, 7);
    }

    @Test
    @DisplayName("createCode 唯一性连续冲突 10 次 → INTERNAL_ERROR（Service 抛出，Controller 传播）")
    void createCode_uniqueConflict() {
        doThrow(new BizException(ErrorCode.INTERNAL_ERROR))
                .when(adminService).createInviteCode(eq(tenantId), eq(adminUserId), eq(10), eq(30));

        assertThatThrownBy(() -> controller.createCode(null, adminAuth()))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.INTERNAL_ERROR.code());
    }

    // ===== batchCreateCodes =====

    @Test
    @DisplayName("batchCreateCodes 默认 30 个一人一码")
    void batchCreateCodes_default() {
        List<String> codes = java.util.stream.IntStream.range(0, 30)
                .mapToObj(i -> "CODE" + i).toList();
        when(adminService.batchCreateCodes(tenantId, adminUserId, 30, 90))
                .thenReturn(new AdminService.BatchResult("BATCH-1", 30, codes));

        var resp = controller.batchCreateCodes(null, adminAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("count")).isEqualTo(30);
        assertThat(resp.data().get("batchId")).isEqualTo("BATCH-1");
        assertThat(((List<?>) resp.data().get("codes"))).hasSize(30);
        verify(adminService).batchCreateCodes(tenantId, adminUserId, 30, 90);
    }

    @Test
    @DisplayName("batchCreateCodes count 超过 200 → clamp 到 200")
    void batchCreateCodes_clampCount() {
        List<String> codes = java.util.stream.IntStream.range(0, 200)
                .mapToObj(i -> "CODE" + i).toList();
        when(adminService.batchCreateCodes(tenantId, adminUserId, 200, 90))
                .thenReturn(new AdminService.BatchResult("BATCH-2", 200, codes));

        var resp = controller.batchCreateCodes(Map.of("count", 500), adminAuth());

        assertThat(resp.data().get("count")).isEqualTo(200);
        verify(adminService).batchCreateCodes(tenantId, adminUserId, 200, 90);
    }

    // ===== listCodes / deactivateCode / deleteCode =====

    @Test
    @DisplayName("listCodes 按租户查询邀请码")
    void listCodes() {
        when(adminService.listInviteCodes(tenantId)).thenReturn(List.of(new TrialInviteCode()));

        var resp = controller.listCodes(adminAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data()).hasSize(1);
        verify(adminService).listInviteCodes(tenantId);
    }

    @Test
    @DisplayName("deactivateCode 邀请码不存在 → RESOURCE_NOT_FOUND（Service 抛出）")
    void deactivateCode_notFound() {
        doThrow(new BizException(ErrorCode.RESOURCE_NOT_FOUND))
                .when(adminService).deactivateInviteCode(eq(tenantId), any(UUID.class));

        assertThatThrownBy(() -> controller.deactivateCode(UUID.randomUUID(), adminAuth()))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.code());
    }

    @Test
    @DisplayName("deactivateCode 成功 → 调用服务（租户条件内置 Service）")
    void deactivateCode_success() {
        UUID codeId = UUID.randomUUID();

        var resp = controller.deactivateCode(codeId, adminAuth());

        assertThat(resp.code()).isEqualTo(0);
        verify(adminService).deactivateInviteCode(tenantId, codeId);
    }

    @Test
    @DisplayName("deleteCode → 调用服务（租户条件内置 Service）")
    void deleteCode() {
        UUID codeId = UUID.randomUUID();

        var resp = controller.deleteCode(codeId, adminAuth());

        assertThat(resp.code()).isEqualTo(0);
        verify(adminService).deleteInviteCode(tenantId, codeId);
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
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        controller.downloadTemplate(response);

        verify(response).setContentType("text/csv; charset=UTF-8");
        verify(response).setHeader("Content-Disposition", "attachment; filename=student_import_template.csv");
        String body = sw.toString();
        assertThat(body).startsWith("\uFEFF");
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
    @DisplayName("importStudents 成功：透传 Service 结果")
    void importStudents_success() {
        when(adminService.importStudents(eq(tenantId), eq(adminUserId), any(InputStream.class)))
                .thenReturn(new AdminService.ImportResult(2, 0, List.of()));
        MockMultipartFile file = new MockMultipartFile("file", "students.csv",
                "text/csv", "\uFEFF昵称,年级,班级\n小明,四年级,2班\n小红,五年级,1班\n".getBytes(StandardCharsets.UTF_8));

        var resp = controller.importStudents(file, adminAuth());

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("created")).isEqualTo(2);
        assertThat(resp.data().get("skipped")).isEqualTo(0);
        verify(adminService).importStudents(eq(tenantId), eq(adminUserId), any(InputStream.class));
    }

    @Test
    @DisplayName("importStudents 重复昵称跳过 → 透传 skipped/errors")
    void importStudents_skipDuplicates() {
        when(adminService.importStudents(eq(tenantId), eq(adminUserId), any(InputStream.class)))
                .thenReturn(new AdminService.ImportResult(1, 1, List.of("第3行：\"小明\" 已存在，跳过")));
        MockMultipartFile file = new MockMultipartFile("file", "students.csv",
                "text/csv", "昵称,年级,班级\n\n小明,四年级,2班\n小红,五年级,1班\n".getBytes(StandardCharsets.UTF_8));

        var resp = controller.importStudents(file, adminAuth());

        assertThat(resp.data().get("created")).isEqualTo(1);
        assertThat(resp.data().get("skipped")).isEqualTo(1);
        assertThat(((List<?>) resp.data().get("errors"))).hasSize(1);
    }

    @Test
    @DisplayName("importStudents 缺昵称行 → 透传 errors + skipped")
    void importStudents_missingPseudonym() {
        when(adminService.importStudents(eq(tenantId), eq(adminUserId), any(InputStream.class)))
                .thenReturn(new AdminService.ImportResult(1, 1, List.of("第2行：缺少昵称")));
        MockMultipartFile file = new MockMultipartFile("file", "students.csv",
                "text/csv", "小明,四年级,2班\n,五年级,1班\n".getBytes(StandardCharsets.UTF_8));

        var resp = controller.importStudents(file, adminAuth());

        assertThat(resp.data().get("created")).isEqualTo(1);
        assertThat(resp.data().get("skipped")).isEqualTo(1);
        assertThat(((List<?>) resp.data().get("errors"))).hasSize(1);
    }

    @Test
    @DisplayName("importStudents CSV 解析异常 → INTERNAL_ERROR（Service 抛出）")
    void importStudents_parseError() {
        doThrow(new BizException(ErrorCode.INTERNAL_ERROR, "CSV 解析失败: disk error"))
                .when(adminService).importStudents(eq(tenantId), eq(adminUserId), any(InputStream.class));
        MockMultipartFile file = new MockMultipartFile("file", "students.csv",
                "text/csv", "小明,四年级,2班".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> controller.importStudents(file, adminAuth()))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.INTERNAL_ERROR.code());
    }

    // ===== getAuditLogs =====

    @Test
    @DisplayName("getAuditLogs 无 action → 全部日志（limit 默认 200）")
    void getAuditLogs_all() {
        when(adminService.getAuditLogs(tenantId, null, 200)).thenReturn(List.of(new AuditLog()));

        var resp = controller.getAuditLogs(adminAuth(), null, 200);

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data()).hasSize(1);
        verify(adminService).getAuditLogs(tenantId, null, 200);
    }

    @Test
    @DisplayName("getAuditLogs 带 action → 按动作过滤")
    void getAuditLogs_byAction() {
        when(adminService.getAuditLogs(tenantId, "LOGIN", 200)).thenReturn(List.of());

        controller.getAuditLogs(adminAuth(), "LOGIN", 200);

        verify(adminService).getAuditLogs(tenantId, "LOGIN", 200);
    }
}
