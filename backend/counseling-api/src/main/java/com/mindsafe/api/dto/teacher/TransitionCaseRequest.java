package com.mindsafe.api.dto.teacher;

/**
 * 个案阶段推进请求（F11：transitionCase 请求体类型化，替代 Map&lt;String, String&gt;）。
 * <p>
 * 字段语义与原 Map 约定完全一致：targetStage 缺省为 ASSESSMENT；
 * 非法阶段值仍由 controller 校验并抛 400（PARAM_INVALID）。
 */
public record TransitionCaseRequest(String targetStage) {
    public TransitionCaseRequest {
        if (targetStage == null) {
            targetStage = "ASSESSMENT";
        }
    }
}
