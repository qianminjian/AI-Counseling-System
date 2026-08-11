package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.AuditLog;
import com.mindsafe.domain.entity.TrialInviteCode;
import com.mindsafe.service.admin.AdminService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 管理端 API - 邀请码管理
 * <p>
 * 功能：生成邀请码 / 列表 / 停用 / 删除 / 批量导入学生 / 审计日志查询
 * 权限：仅 admin 角色可访问（由 SecurityConfig 控制）
 * <p>
 * T4 批次B/C：全部 SQL 下沉 AdminService（租户条件强制内置），Controller 不再直查 Mapper。
 */
@RestController
@RequestMapping("/api/v1/admin/invite-codes")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /** 生成邀请码 */
    @PostMapping
    public ApiResponse<TrialInviteCode> createCode(
            @RequestBody(required = false) Map<String, Object> body,
            Authentication auth) {
        TenantContext ctx = extractContext(auth);
        UUID userId = (UUID) auth.getPrincipal();

        int maxUses = 10; // 默认 10 次
        int expireDays = 30; // 默认 30 天有效

        if (body != null) {
            if (body.containsKey("maxUses")) {
                maxUses = ((Number) body.get("maxUses")).intValue();
            }
            if (body.containsKey("expireDays")) {
                expireDays = ((Number) body.get("expireDays")).intValue();
            }
        }

        TrialInviteCode code = adminService.createInviteCode(ctx.tenantId(), userId, maxUses, expireDays);
        return ApiResponse.ok(code);
    }

    /**
     * 批量生成一人一码邀请码（教师分发给学生）
     * POST /api/v1/admin/invite-codes/batch
     * Body: { "count": 30, "expireDays": 90 }
     */
    @PostMapping("/batch")
    public ApiResponse<Map<String, Object>> batchCreateCodes(
            @RequestBody(required = false) Map<String, Object> body,
            Authentication auth) {
        TenantContext ctx = extractContext(auth);
        UUID userId = (UUID) auth.getPrincipal();

        int count = 30;
        int expireDays = 90;
        if (body != null) {
            if (body.containsKey("count")) count = Math.min(((Number) body.get("count")).intValue(), 200);
            if (body.containsKey("expireDays")) expireDays = ((Number) body.get("expireDays")).intValue();
        }

        AdminService.BatchResult r = adminService.batchCreateCodes(ctx.tenantId(), userId, count, expireDays);
        return ApiResponse.ok(Map.of(
                "batchId", r.batchId(),
                "count", r.count(),
                "codes", r.codes(),
                "expireDays", expireDays
        ));
    }

    /** 邀请码列表 */
    @GetMapping
    public ApiResponse<List<TrialInviteCode>> listCodes(Authentication auth) {
        TenantContext ctx = extractContext(auth);
        return ApiResponse.ok(adminService.listInviteCodes(ctx.tenantId()));
    }

    /** 停用邀请码 */
    @PatchMapping("/{codeId}/deactivate")
    public ApiResponse<Void> deactivateCode(@PathVariable UUID codeId, Authentication auth) {
        TenantContext ctx = extractContext(auth);
        adminService.deactivateInviteCode(ctx.tenantId(), codeId);
        return ApiResponse.ok(null);
    }

    /** 删除邀请码 */
    @DeleteMapping("/{codeId}")
    public ApiResponse<Void> deleteCode(@PathVariable UUID codeId, Authentication auth) {
        TenantContext ctx = extractContext(auth);
        adminService.deleteInviteCode(ctx.tenantId(), codeId);
        return ApiResponse.ok(null);
    }

    private TenantContext extractContext(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof TenantContext ctx)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return ctx;
    }

    // ===== 批量导入学生 =====

    /** 下载导入模板 CSV */
    @GetMapping("/import-template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=student_import_template.csv");
        // BOM for Excel（先取 Writer 再写 BOM，避免 getOutputStream/getWriter 混用抛 IllegalStateException）
        var writer = response.getWriter();
        writer.print('\uFEFF');
        writer.println("昵称,年级,班级");
        writer.println("小明,四年级,2班");
        writer.println("小红,五年级,1班");
        writer.flush();
    }

    /**
     * 批量导入学生（CSV 格式）
     * CSV 列：昵称,年级,班级（如：小明,四年级,2班）
     * 自动分配初始密码（学号后 6 位），首次登录强制改密
     */
    @PostMapping("/import-students")
    public ApiResponse<Map<String, Object>> importStudents(
            @RequestParam("file") MultipartFile file,
            Authentication auth) {
        TenantContext ctx = extractContext(auth);
        UUID userId = (UUID) auth.getPrincipal();

        if (file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文件为空");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".csv")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "仅支持 CSV 文件");
        }

        try {
            AdminService.ImportResult r = adminService.importStudents(ctx.tenantId(), userId, file.getInputStream());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("created", r.created());
            result.put("skipped", r.skipped());
            result.put("errors", r.errors());
            return ApiResponse.ok(result);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "CSV 解析失败: " + e.getMessage());
        }
    }

    // ===== 审计日志查询（doing/92 R-002：迁出至 AuditLogController /api/v1/admin/audit-logs） =====

    /**
     * 查询审计日志（兼容别名，deprecated：审计日志为全局资源，请用 /api/v1/admin/audit-logs）
     */
    @GetMapping("/audit-logs")
    @Deprecated
    public ApiResponse<List<AuditLog>> getAuditLogs(
            Authentication auth,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "200") int limit) {
        TenantContext ctx = extractContext(auth);
        return ApiResponse.ok(adminService.getAuditLogs(ctx.tenantId(), action, limit));
    }
}
