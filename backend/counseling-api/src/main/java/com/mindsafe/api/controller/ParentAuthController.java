package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.domain.entity.ParentAccount;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.auth.ParentAuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 家长认证 API（FAM-003/004）
 * <ul>
 *   <li>POST /register — 家庭码 + 手机号 + 密码 + 关系 → 注册并绑定</li>
 *   <li>POST /login — 手机号 + 密码 → 签发 JWT</li>
 *   <li>GET /children — 查询绑定的学生列表</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/parent/auth")
public class ParentAuthController {

    private final ParentAuthService parentAuthService;
    private final JwtTokenProvider jwtTokenProvider;

    public ParentAuthController(ParentAuthService parentAuthService,
                                JwtTokenProvider jwtTokenProvider) {
        this.parentAuthService = parentAuthService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * 家长注册（家庭码 + 手机号 + 密码 + 关系）
     */
    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody ParentRegisterRequest request) {
        ParentAccount account = parentAuthService.register(
                request.familyCode(), request.phone(), request.password(), request.relation());

        // 签发 JWT（userType=parent）
        String token = jwtTokenProvider.generateToken(
                account.getParentId(), "parent", account.getTenantId());
        String refreshToken = jwtTokenProvider.generateRefreshToken(
                account.getParentId(), "parent", account.getTenantId());

        // 查询绑定的学生
        List<User> students = parentAuthService.getLinkedStudents(account.getParentId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("refreshToken", refreshToken);
        result.put("parentId", account.getParentId());
        result.put("displayName", account.getDisplayName());
        result.put("children", students.stream().map(s -> Map.of(
                "userId", s.getUserId(),
                "nickname", s.getPseudonym(),
                "gradeCode", s.getGradeCode() != null ? s.getGradeCode() : "",
                "classCode", s.getClassCode() != null ? s.getClassCode() : ""
        )).collect(Collectors.toList()));

        return ApiResponse.ok(result);
    }

    /**
     * 家长登录（手机号 + 密码）
     */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody ParentLoginRequest request) {
        ParentAccount account = parentAuthService.login(request.phone(), request.password());

        String token = jwtTokenProvider.generateToken(
                account.getParentId(), "parent", account.getTenantId());
        String refreshToken = jwtTokenProvider.generateRefreshToken(
                account.getParentId(), "parent", account.getTenantId());

        List<User> students = parentAuthService.getLinkedStudents(account.getParentId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("refreshToken", refreshToken);
        result.put("parentId", account.getParentId());
        result.put("displayName", account.getDisplayName());
        result.put("children", students.stream().map(s -> Map.of(
                "userId", s.getUserId(),
                "nickname", s.getPseudonym(),
                "gradeCode", s.getGradeCode() != null ? s.getGradeCode() : "",
                "classCode", s.getClassCode() != null ? s.getClassCode() : ""
        )).collect(Collectors.toList()));

        return ApiResponse.ok(result);
    }

    /**
     * 查询绑定的学生列表（需已登录）
     */
    @GetMapping("/children")
    public ApiResponse<List<Map<String, Object>>> getChildren(
            @RequestHeader("Authorization") String authHeader) {
        String token = authHeader.replace("Bearer ", "");
        UUID parentId = jwtTokenProvider.getUserId(token);

        List<User> students = parentAuthService.getLinkedStudents(parentId);
        List<Map<String, Object>> children = students.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", s.getUserId());
            m.put("nickname", s.getPseudonym());
            m.put("gradeCode", s.getGradeCode());
            m.put("classCode", s.getClassCode());
            return m;
        }).collect(Collectors.toList());

        return ApiResponse.ok(children);
    }

    // ===== Request Records =====

    public record ParentRegisterRequest(
            @NotBlank(message = "家庭码不能为空") String familyCode,
            @NotBlank(message = "手机号不能为空")
            @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确") String phone,
            @NotBlank(message = "密码不能为空") String password,
            String relation
    ) {}

    public record ParentLoginRequest(
            @NotBlank(message = "手机号不能为空") String phone,
            @NotBlank(message = "密码不能为空") String password
    ) {}
}
