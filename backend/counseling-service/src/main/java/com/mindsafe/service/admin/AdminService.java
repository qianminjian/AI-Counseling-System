package com.mindsafe.service.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.AuditLog;
import com.mindsafe.domain.entity.TrialInviteCode;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.AuditLogMapper;
import com.mindsafe.domain.mapper.TrialInviteCodeMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.audit.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 管理端服务（T4 批次B/C：邀请码 CRUD / 学生批量导入 / 审计日志查询下沉，Controller 不再直查 Mapper）。
 * <p>
 * 租户条件单点：deactivate/delete 强制 eq(tenantId)，杜绝跨租户操作邀请码。
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TrialInviteCodeMapper inviteCodeMapper;
    private final UserMapper userMapper;
    private final AuditLogMapper auditLogMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    public AdminService(TrialInviteCodeMapper inviteCodeMapper,
                        UserMapper userMapper,
                        AuditLogMapper auditLogMapper,
                        PasswordEncoder passwordEncoder,
                        AuditLogService auditLogService) {
        this.inviteCodeMapper = inviteCodeMapper;
        this.userMapper = userMapper;
        this.auditLogMapper = auditLogMapper;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    /** 生成单个邀请码 */
    public TrialInviteCode createInviteCode(UUID tenantId, UUID userId, int maxUses, int expireDays) {
        TrialInviteCode code = new TrialInviteCode();
        code.setCodeId(UUID.randomUUID());
        code.setTenantId(tenantId);
        code.setCode(generateUniqueCode());
        code.setMaxUses(maxUses);
        code.setUsedCount(0);
        code.setExpiresAt(Instant.now().plus(expireDays, ChronoUnit.DAYS));
        code.setStatus(TrialInviteCode.STATUS_ACTIVE);
        code.setCreatedBy(userId);
        code.setCreatedAt(Instant.now());
        inviteCodeMapper.insert(code);
        return code;
    }

    /** 批量生成一人一码邀请码（教师分发给学生），内部留痕 */
    @Transactional
    public BatchResult batchCreateCodes(UUID tenantId, UUID userId, int count, int expireDays) {
        String batchId = "BATCH-" + System.currentTimeMillis();
        List<String> codes = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            TrialInviteCode code = new TrialInviteCode();
            code.setCodeId(UUID.randomUUID());
            code.setTenantId(tenantId);
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

        auditLogService.log(tenantId, userId, "BATCH_INVITE_CODES",
                "invite_code_batch", null, "生成" + count + "个邀请码，批次:" + batchId);
        return new BatchResult(batchId, count, codes);
    }

    /** 邀请码列表（同租户） */
    public List<TrialInviteCode> listInviteCodes(UUID tenantId) {
        return inviteCodeMapper.selectList(
                new LambdaQueryWrapper<TrialInviteCode>()
                        .eq(TrialInviteCode::getTenantId, tenantId)
                        .orderByDesc(TrialInviteCode::getCreatedAt)
        );
    }

    /** 停用邀请码（T4：租户条件强制内置，防跨租户操作） */
    public void deactivateInviteCode(UUID tenantId, UUID codeId) {
        TrialInviteCode code = inviteCodeMapper.selectOne(
                new LambdaQueryWrapper<TrialInviteCode>()
                        .eq(TrialInviteCode::getTenantId, tenantId)
                        .eq(TrialInviteCode::getCodeId, codeId)
        );
        if (code == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        code.setStatus("disabled");
        inviteCodeMapper.updateById(code);
    }

    /** 删除邀请码（T4：租户条件强制内置，防跨租户操作） */
    public void deleteInviteCode(UUID tenantId, UUID codeId) {
        inviteCodeMapper.delete(
                new LambdaQueryWrapper<TrialInviteCode>()
                        .eq(TrialInviteCode::getTenantId, tenantId)
                        .eq(TrialInviteCode::getCodeId, codeId)
        );
    }

    /**
     * 批量导入学生（CSV 解析 + 去重 + 创建，含审计；密码编码在事务内）。
     * 文件名校验（.csv）留在 Controller（HTTP 层职责）。
     */
    @Transactional
    public ImportResult importStudents(UUID tenantId, UUID userId, InputStream csvStream) {
        int created = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(csvStream, StandardCharsets.UTF_8))) {
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
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "CSV 解析失败: " + e.getMessage());
        }

        // 审计：批量导入
        auditLogService.log(tenantId, userId, "IMPORT_STUDENTS", "batch",
                null, "{\"created\":" + created + ",\"skipped\":" + skipped + "}");
        return new ImportResult(created, skipped, errors);
    }

    /** 审计日志查询（admin 专用，最近 limit 条；AUD-043 分页插件安全化） */
    public List<AuditLog> getAuditLogs(UUID tenantId, String action, int limit) {
        var wrapper = new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getTenantId, tenantId)
                .orderByDesc(AuditLog::getCreatedAt);
        if (action != null && !action.isBlank()) {
            wrapper.eq(AuditLog::getAction, action);
        }
        var pageResult = auditLogMapper.selectPage(new Page<>(1, Math.min(limit, 500), false), wrapper);
        return pageResult.getRecords();
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

    /** 批量生成结果 */
    public record BatchResult(String batchId, int count, List<String> codes) {
    }

    /** 学生导入结果 */
    public record ImportResult(int created, int skipped, List<String> errors) {
    }
}
