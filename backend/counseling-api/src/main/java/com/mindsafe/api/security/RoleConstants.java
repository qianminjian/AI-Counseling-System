package com.mindsafe.api.security;

import com.mindsafe.domain.entity.User;

import java.util.Set;

/**
 * 角色清单共享常量单点（审计 F4）
 * <p>
 * 教师五角色此前双维护：AlertWebSocketHandler.ALERT_ROLES（小写 userType）与
 * SecurityConfig 三处 hasAnyRole（大写角色名）。BUG-T-RC-01（HEAD_TEACHER 漏配致全接口 403）
 * 已证明双维护会漏配。本类以 User.USER_TYPE_* 为唯一事实源派生两种形态：
 * - ALERT_ACCESS_USER_TYPES：预警推送接入判断（userType claim 小写）
 * - teacherAlertAuthorities()：Spring Security hasAnyRole 参数（大写，自动 ROLE_ 前缀）
 */
public final class RoleConstants {

    /** 预警推送/教师端/预警/数据分析共享角色清单（与 design/08 §2.9 对齐，学生/家长严禁接入） */
    public static final Set<String> ALERT_ACCESS_USER_TYPES = Set.of(
            User.USER_TYPE_TEACHER,
            User.USER_TYPE_PSYCH_TEACHER,
            User.USER_TYPE_CLASS_TEACHER,
            User.USER_TYPE_HEAD_TEACHER,
            User.USER_TYPE_ADMIN);

    private RoleConstants() {
    }

    /** Spring Security hasAnyRole 参数（大写；单源派生，防止小写/大写双维护漂移） */
    public static String[] teacherAlertAuthorities() {
        return ALERT_ACCESS_USER_TYPES.stream().map(String::toUpperCase).toArray(String[]::new);
    }
}
