package com.mindsafe.service.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.ParentAccount;
import com.mindsafe.domain.entity.ParentStudentLink;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.ParentAccountMapper;
import com.mindsafe.domain.mapper.ParentStudentLinkMapper;
import com.mindsafe.domain.mapper.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 家长认证服务（FAM-003/004）
 * <p>
 * 注册：家庭码 + 手机号 + 密码 + 关系 → 创建家长账号 + 绑定学生
 * 登录：手机号 + 密码 → 签发 JWT
 */
@Service
public class ParentAuthService {

    private static final UUID TRIAL_TENANT_ID =
            UUID.fromString("90000000-0000-0000-0000-000000000001");

    private final ParentAccountMapper parentAccountMapper;
    private final ParentStudentLinkMapper parentStudentLinkMapper;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public ParentAuthService(ParentAccountMapper parentAccountMapper,
                             ParentStudentLinkMapper parentStudentLinkMapper,
                             UserMapper userMapper,
                             PasswordEncoder passwordEncoder) {
        this.parentAccountMapper = parentAccountMapper;
        this.parentStudentLinkMapper = parentStudentLinkMapper;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 家长注册：家庭码 + 手机号 + 密码 + 关系
     *
     * @return 创建的家长账号
     */
    @Transactional
    public ParentAccount register(String familyCode, String phone, String password, String relation) {
        // 1. 校验手机号格式
        if (phone == null || !phone.matches("^1[3-9]\\d{9}$")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "手机号格式不正确");
        }

        // 2. 校验密码强度（≥6位，MVP 简化）
        if (password == null || password.length() < 6) {
            throw new BizException(ErrorCode.PARAM_INVALID, "密码至少 6 位");
        }

        // 3. 校验家庭码 → 找到学生
        User student = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getTenantId, TRIAL_TENANT_ID)
                        .eq(User::getFamilyCode, familyCode != null ? familyCode.toUpperCase() : "")
                        .eq(User::getStatus, "active")
                        .last("LIMIT 1")
        );
        if (student == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "家庭码无效，请核对后重试");
        }

        // 4. 检查手机号是否已注册
        ParentAccount existing = parentAccountMapper.selectOne(
                new LambdaQueryWrapper<ParentAccount>()
                        .eq(ParentAccount::getTenantId, TRIAL_TENANT_ID)
                        .eq(ParentAccount::getPhone, phone)
                        .last("LIMIT 1")
        );
        if (existing != null) {
            // 已注册：检查是否已绑定该学生，未绑定则追加绑定
            ParentStudentLink existLink = parentStudentLinkMapper.selectOne(
                    new LambdaQueryWrapper<ParentStudentLink>()
                            .eq(ParentStudentLink::getParentId, existing.getParentId())
                            .eq(ParentStudentLink::getStudentUserId, student.getUserId())
                            .last("LIMIT 1")
            );
            if (existLink != null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "该手机号已绑定此学生，请直接登录");
            }
            // 追加绑定
            createLink(existing.getParentId(), student.getUserId(), relation);
            return existing;
        }

        // 5. 创建家长账号
        ParentAccount account = new ParentAccount();
        account.setParentId(UUID.randomUUID());
        account.setTenantId(TRIAL_TENANT_ID);
        account.setPhone(phone);
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setDisplayName(buildDisplayName(relation));
        account.setStatus("active");
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        parentAccountMapper.insert(account);

        // 6. 创建关联
        createLink(account.getParentId(), student.getUserId(), relation);

        return account;
    }

    /**
     * 家长登录：手机号 + 密码
     */
    public ParentAccount login(String phone, String password) {
        ParentAccount account = parentAccountMapper.selectOne(
                new LambdaQueryWrapper<ParentAccount>()
                        .eq(ParentAccount::getTenantId, TRIAL_TENANT_ID)
                        .eq(ParentAccount::getPhone, phone)
                        .eq(ParentAccount::getStatus, "active")
                        .last("LIMIT 1")
        );
        if (account == null || !passwordEncoder.matches(password, account.getPasswordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "手机号或密码错误");
        }

        // 更新最后登录时间
        ParentAccount update = new ParentAccount();
        update.setParentId(account.getParentId());
        update.setLastLoginAt(Instant.now());
        parentAccountMapper.updateById(update);

        return account;
    }

    /**
     * 查询家长绑定的所有学生
     */
    public List<User> getLinkedStudents(UUID parentId) {
        List<ParentStudentLink> links = parentStudentLinkMapper.selectList(
                new LambdaQueryWrapper<ParentStudentLink>()
                        .eq(ParentStudentLink::getParentId, parentId)
        );
        return links.stream()
                .map(link -> userMapper.selectById(link.getStudentUserId()))
                .filter(u -> u != null && "active".equals(u.getStatus()))
                .toList();
    }

    private void createLink(UUID parentId, UUID studentUserId, String relation) {
        ParentStudentLink link = new ParentStudentLink();
        link.setLinkId(UUID.randomUUID());
        link.setTenantId(TRIAL_TENANT_ID);
        link.setParentId(parentId);
        link.setStudentUserId(studentUserId);
        link.setRelation(relation != null ? relation : "parent");
        link.setCreatedAt(Instant.now());
        parentStudentLinkMapper.insert(link);
    }

    private String buildDisplayName(String relation) {
        return switch (relation != null ? relation : "") {
            case "father" -> "爸爸";
            case "mother" -> "妈妈";
            case "grandparent" -> "祖父母";
            default -> "家长";
        };
    }
}
