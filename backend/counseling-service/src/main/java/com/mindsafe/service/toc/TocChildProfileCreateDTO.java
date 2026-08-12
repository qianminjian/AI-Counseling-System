package com.mindsafe.service.toc;

/**
 * 家庭档案创建请求 DTO（P2-3，doing/97：替代裸 Map——key 拼写错误编译期可发现）。
 * <p>
 * 更新档案仍用 Map（containsKey 区分"未传"与"传 null"语义），本 DTO 仅用于创建。
 */
public record TocChildProfileCreateDTO(String nickname, Integer age, String gender, String interests) {
}
