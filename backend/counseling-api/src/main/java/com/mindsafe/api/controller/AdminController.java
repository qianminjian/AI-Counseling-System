package com.mindsafe.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.TrialInviteCode;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.TrialInviteCodeMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.domain.entity.AuditLog;
import com.mindsafe.domain.mapper.AuditLogMapper;
import com.mindsafe.service.audit.AuditLogService;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 管理端 API - 邀请码管理
 * <p>
 * 功能：生成邀请码 / 列表 / 停用 / 删除
 * 权限：仅 admin 角色可访问（由 SecurityConfig 控制）
 */
@RestController
@RequestMapping("/api/v1/admin/invite-codes")
public class AdminController {

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TrialInviteCodeMapper inviteCodeMapper;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final AuditLogMapper auditLogMapper;

    public AdminController(TrialInviteCodeMapper inviteCodeMapper,
                           UserMapper userMapper,
                           PasswordEncoder passwordEncoder,
                           AuditLogService auditLogService,
                           AuditLogMapper auditLogMapper) {
        this.inviteCodeMapper = inviteCodeMapper;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
        this.auditLogMapper = auditLogMapper;
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

        TrialInviteCode code = new TrialInviteCode();
        code.setCodeId(UUID.randomUUID());
        code.setTenantId(ctx.tenantId());
        code.setCode(generateUniqueCode());
        code.setMaxUses(maxUses);
        code.setUsedCount(0);
        code.setExpiresAt(Instant.now().plus(expireDays, ChronoUnit.DAYS));
        code.setStatus(TrialInviteCode.STATUS_ACTIVE);
        code.setCreatedBy(userId);
        code.setCreatedAt(Instant.now());

        inviteCodeMapper.insert(code);
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

        String batchId = "BATCH-" + System.currentTimeMillis();
        List<String> codes = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            TrialInviteCode code = new TrialInviteCode();
            code.setCodeId(UUID.randomUUID());
            code.setTenantId(ctx.tenantId());
            code.setCode(generateUniqueCode());
            code.setMaxUses(1); // 一人一码
            code.setUsedCount(0);
            code.setExpiresAt(Instant.now().plus(expireDays, ChronoUnit.DAYS));
            code.setStatus(TrialInviteCode.STATUS_ACTIVE);
            code.setCreatedBy(userId);
            code.setCreatedAt(Instant.now());
            code.setBatchId(batchId);
            code.setGeneratedBy(userId);
            inviteCodeMapper.insert(code);
            codes.add(code.getCode());
        }

        auditLogService.log(ctx.tenantId(), userId, "BATCH_INVITE_CODES",
                "invite_code_batch", null, "生成" + count + "个邀请码，批次:" + batchId);

        return ApiResponse.ok(Map.of(
                "batchId", batchId,
                "count", count,
                "codes", codes,
                "expireDays", expireDays
        ));
    }

    /** 邀请码列表 */
    @GetMapping
    public ApiResponse<List<TrialInviteCode>> listCodes(Authentication auth) {
        TenantContext ctx = extractContext(auth);
        List<TrialInviteCode> codes = inviteCodeMapper.selectList(
                new LambdaQueryWrapper<TrialInviteCode>()
                        .eq(TrialInviteCode::getTenantId, ctx.tenantId())
                        .orderByDesc(TrialInviteCode::getCreatedAt)
        );
        return ApiResponse.ok(codes);
    }

    /** 停用邀请码 */
    @PatchMapping("/{codeId}/deactivate")
    public ApiResponse<Void> deactivateCode(@PathVariable UUID codeId, Authentication auth) {
        extractContext(auth); // 验证身份
        TrialInviteCode code = inviteCodeMapper.selectById(codeId);
        if (code == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        code.setStatus("disabled");
        inviteCodeMapper.updateById(code);
        return ApiResponse.ok(null);
    }

    /** 删除邀请码 */
    @DeleteMapping("/{codeId}")
    public ApiResponse<Void> deleteCode(@PathVariable UUID codeId, Authentication auth) {
        extractContext(auth);
        inviteCodeMapper.deleteById(codeId);
        return ApiResponse.ok(null);
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
            }
            String candidate = sb.toString();
            // 检查唯一性
            Long count = inviteCodeMapper.selectCount(
                    new LambdaQueryWrapper<TrialInviteCode>()
                            .eq(TrialInviteCode::getCode, candidate)
            );
            if (count == 0) return candidate;
        }
        throw new BizException(ErrorCode.INTERNAL_ERROR);
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
    public void downloadTemplate(HttpServletResponse response) throws java.io.IOException {
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=student_import_template.csv");
        // BOM for Excel
        response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        var writer = response.getWriter();
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
        UUID tenantId = ctx.tenantId();

        if (file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文件为空");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".csv")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "仅支持 CSV 文件");
        }

        int created = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                // 跳过 BOM 和表头
                if (lineNo == 1) {
                    line = line.replace("\uFEFF", "");
                    if (line.contains("昵称") || line.toLowerCase().contains("nickname")) continue;
                }
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(",");
                if (parts.length < 1 || parts[0].trim().isEmpty()) {
                    errors.add("第" + lineNo + "行：缺少昵称");
                    skipped++;
                    continue;
                }

                String pseudonym = parts[0].trim();
                String gradeCode = parts.length > 1 ? parts[1].trim() : "";
                String classCode = parts.length > 2 ? parts[2].trim() : "";

                // 检查同租户下是否已存在同名学生
                Long exists = userMapper.selectCount(
                        new LambdaQueryWrapper<User>()
                                .eq(User::getTenantId, tenantId)
                                .eq(User::getPseudonym, pseudonym)
                                .eq(User::getUserType, User.USER_TYPE_STUDENT)
                );
                if (exists > 0) {
                    errors.add("第" + lineNo + "行：\"" + pseudonym + "\" 已存在，跳过");
                    skipped++;
                    continue;
                }

                // 创建学生用户
                User student = User.createStudent(tenantId, null, pseudonym, gradeCode, classCode);
                student.setUserId(UUID.randomUUID());
                // 初始密码：随机 6 位数字
                String initPwd = String.format("%06d", RANDOM.nextInt(1000000));
                student.setPasswordHash(passwordEncoder.encode(initPwd));
                student.setMustChangePassword(true);
                userMapper.insert(student);
                created++;
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "CSV 解析失败: " + e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("skipped", skipped);
        result.put("errors", errors);

        // 审计：批量导入
        UUID userId = (UUID) auth.getPrincipal();
        auditLogService.log(tenantId, userId, "IMPORT_STUDENTS", "batch",
                null, "{\"created\":" + created + ",\"skipped\":" + skipped + "}");

        return ApiResponse.ok(result);
    }

    // ===== 审计日志查询 =====

    /** 查询审计日志（admin 专用，最近 200 条） */
    @GetMapping("/audit-logs")
    public ApiResponse<List<AuditLog>> getAuditLogs(
            Authentication auth,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "200") int limit) {
        TenantContext ctx = extractContext(auth);
        var wrapper = new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getTenantId, ctx.tenantId())
                .orderByDesc(AuditLog::getCreatedAt)
                .last("LIMIT " + Math.min(limit, 500));
        if (action != null && !action.isBlank()) {
            wrapper.eq(AuditLog::getAction, action);
        }
        return ApiResponse.ok(auditLogMapper.selectList(wrapper));
    }
}
