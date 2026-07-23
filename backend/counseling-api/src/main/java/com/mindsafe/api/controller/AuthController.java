package com.mindsafe.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.UserMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 认证 API（M1：学号/ pseudonym + 密码登录 → JWT）
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthController(UserMapper userMapper,
                          PasswordEncoder passwordEncoder,
                          JwtTokenProvider jwtTokenProvider) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        // 按 pseudonym 查找用户（M1 简化：pseudonym 作为登录名）
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getPseudonym, request.username())
                        .eq(User::getStatus, "active")
                        .last("LIMIT 1")
        );

        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        // 更新最后登录时间
        User update = new User();
        update.setUserId(user.getUserId());
        update.setLastLoginAt(Instant.now());
        userMapper.updateById(update);

        // 生成 JWT
        String token = jwtTokenProvider.generateToken(
                user.getUserId(), user.getUserType(), user.getTenantId());

        return ApiResponse.ok(new LoginResponse(
                token,
                user.getUserId(),
                user.getPseudonym(),
                user.getUserType(),
                user.getGradeCode(),
                user.getClassCode()
        ));
    }

    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password
    ) {}

    public record LoginResponse(
            String token,
            UUID userId,
            String displayName,
            String userType,
            String gradeCode,
            String classCode
    ) {}
}
