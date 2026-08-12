package com.mindsafe.api.gate;

import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.service.consent.GuardianConsentService;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 监护人同意门禁（F10：PIPL §31 未成年人单独同意）service 层强制。
 * <p>
 * 背景：同意检查此前散落在 ChatController 三个端点手动调用 requireGuardianConsent，
 * 属"调用方自觉"防线——新增对话类端点若忘记调用即漏检（红线风险）。
 * 本切面将门禁下沉到 ConversationService 方法执行前（createSession/sendMessageStream/sendNudgeStream），
 * 从方法参数中取 tenantId/studentUserId 判同意，未同意抛 {@link ErrorCode#CONSENT_REQUIRED}（403/20003）。
 * <p>
 * 语义等价性：
 * <ul>
 *   <li>原 controller 门禁抛出的异常类型/错误码/文案完全一致（由统一异常出口转 403）；</li>
 *   <li>只拦截 3 个学生主动对话入口（创建会话/发消息/暖场），结束会话/历史/评价等管理类操作不拦截，行为不变；</li>
 *   <li>已冻结的 EntitlementFilter（家长端资源授权）本体未改动，家长端流程不受影响。</li>
 * </ul>
 */
@Aspect
@Component
public class GuardianConsentGate {

    private final GuardianConsentService guardianConsentService;

    public GuardianConsentGate(GuardianConsentService guardianConsentService) {
        this.guardianConsentService = guardianConsentService;
    }

    /** 创建会话：createSession(UUID tenantId, UUID studentUserId, ...) */
    @Before("execution(* com.mindsafe.service.conversation.ConversationService.createSession(..))")
    public void gateCreateSession(JoinPoint joinPoint) {
        requireConsent(joinPoint);
    }

    /** 发送消息：sendMessageStream(UUID tenantId, UUID studentUserId, ...)（4 参与 6 参重载均匹配） */
    @Before("execution(* com.mindsafe.service.conversation.ConversationService.sendMessageStream(..))")
    public void gateSendMessage(JoinPoint joinPoint) {
        requireConsent(joinPoint);
    }

    /** 冷场暖场：sendNudgeStream(UUID tenantId, UUID studentUserId, ...) */
    @Before("execution(* com.mindsafe.service.conversation.ConversationService.sendNudgeStream(..))")
    public void gateSendNudge(JoinPoint joinPoint) {
        requireConsent(joinPoint);
    }

    /**
     * 统一门禁判定：方法签名约定参数 0 = tenantId、参数 1 = studentUserId。
     * 参数缺失/为 null 时不拦截（防御性放行，避免误伤；真实调用链必经认证上下文取值）。
     */
    private void requireConsent(JoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        if (args.length < 2 || !(args[0] instanceof UUID tenantId) || !(args[1] instanceof UUID studentUserId)) {
            return;
        }
        if (!guardianConsentService.hasGuardianConsent(tenantId, studentUserId)) {
            throw new BizException(ErrorCode.CONSENT_REQUIRED, "需要先完成监护人同意才能开始对话");
        }
    }
}
