package com.mindsafe.api.controller;

import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.domain.entity.AuditLog;
import com.mindsafe.domain.entity.DegradationEvent;
import com.mindsafe.domain.entity.RiskEvent;
import com.mindsafe.domain.entity.ServiceHealthSnapshot;
import com.mindsafe.service.knowledge.KnowledgeBaseService;
import com.mindsafe.service.monitoring.DegradationMatrixService;
import com.mindsafe.service.monitoring.OpsService;
import com.mindsafe.service.ops.OpsInsightsService;
import com.mindsafe.service.risk.RiskOverviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 运维域端点（ADMIN-P0-05/06/07，M2 服务拓扑/告警只读 + M6 审计跨租户查询）
 * <p>
 * 仅平台角色可访问（SecurityConfig：PLATFORM_SUPER_ADMIN/PLATFORM_OPS_ADMIN/PLATFORM_AUDIT）。
 * 设计见 doing/83 后台管理端 §7.2。
 */
@RestController
@RequestMapping("/api/v1/ops")
public class OpsController {

    /** 高危操作确认短语（code-review M2：任意非空值不构成二次确认） */
    private static final String CONFIRM_PHRASE = "CONFIRM";

    private final OpsService opsService;
    private final RiskOverviewService riskOverviewService;
    private final DegradationMatrixService degradationMatrixService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final OpsInsightsService opsInsightsService;

    public OpsController(OpsService opsService,
                         RiskOverviewService riskOverviewService,
                         DegradationMatrixService degradationMatrixService,
                         KnowledgeBaseService knowledgeBaseService,
                         OpsInsightsService opsInsightsService) {
        this.opsService = opsService;
        this.riskOverviewService = riskOverviewService;
        this.degradationMatrixService = degradationMatrixService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.opsInsightsService = opsInsightsService;
    }

    @GetMapping("/services/status")
    public ApiResponse<Map<String, String>> servicesStatus() {
        return ApiResponse.ok(opsService.servicesStatus());
    }

    @GetMapping("/services/health-history")
    public ApiResponse<List<ServiceHealthSnapshot>> healthHistory(
            @RequestParam(required = false) String service,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(opsService.healthHistory(service, limit));
    }

    @GetMapping("/alerts")
    public ApiResponse<List<Map<String, Object>>> alerts() {
        return ApiResponse.ok(opsService.activeAlerts());
    }

    @GetMapping("/audit-logs")
    public ApiResponse<List<AuditLog>> auditLogs(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(opsService.auditLogs(tenantId, action, startTime, endTime, limit));
    }

    // ===== M8 业务信号（ADMIN-P1-04：风险全景 + 时效监控，纯查询） =====

    @GetMapping("/risk/overview")
    public ApiResponse<Map<String, Object>> riskOverview(@RequestParam(required = false) UUID tenantId) {
        return ApiResponse.ok(riskOverviewService.overview(tenantId));
    }

    @GetMapping("/risk/sla-stats")
    public ApiResponse<List<Map<String, Object>>> riskSlaStats(@RequestParam(required = false) UUID tenantId) {
        return ApiResponse.ok(riskOverviewService.slaStats(tenantId));
    }

    @GetMapping("/risk/overdue")
    public ApiResponse<List<com.mindsafe.domain.entity.RiskEvent>> riskOverdue(@RequestParam(required = false) UUID tenantId) {
        return ApiResponse.ok(riskOverviewService.overdueList(tenantId));
    }

    /** 转派（X-Confirm 固定短语 + reason 必填，高危操作二次确认，§10） */
    @PostMapping("/risk/{riskEventId}/transfer")
    public ApiResponse<Void> transfer(@PathVariable UUID riskEventId,
                                      @RequestHeader(value = "X-Confirm", required = false) String confirm,
                                      @Valid @RequestBody RiskTransferRequest request) {
        if (!CONFIRM_PHRASE.equals(confirm)) {
            return ApiResponse.error(ErrorCode.PARAM_INVALID.code(), "高危操作需 X-Confirm: CONFIRM 头（操作二次确认）");
        }
        // 操作人取认证主体（code-review M3：不信任请求体，防审计身份伪造）
        riskOverviewService.transfer(riskEventId, request.assignToUserId(), operatorName(), request.reason());
        return ApiResponse.ok(null);
    }

    /** 强制关闭（X-Confirm 固定短语 + reason 必填，高危操作二次确认，§10） */
    @PostMapping("/risk/{riskEventId}/force-close")
    public ApiResponse<Void> forceClose(@PathVariable UUID riskEventId,
                                        @RequestHeader(value = "X-Confirm", required = false) String confirm,
                                        @Valid @RequestBody RiskCloseRequest request) {
        if (!CONFIRM_PHRASE.equals(confirm)) {
            return ApiResponse.error(ErrorCode.PARAM_INVALID.code(), "高危操作需 X-Confirm: CONFIRM 头（操作二次确认）");
        }
        riskOverviewService.forceClose(riskEventId, operatorName(), request.reason());
        return ApiResponse.ok(null);
    }

    /** 操作人：平台认证主体（审计身份不可伪造） */
    private String operatorName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return "unknown";
        }
        return auth.getName();
    }

    public record RiskTransferRequest(UUID assignToUserId,
                                      @NotBlank(message = "reason 必填") String reason) {
    }

    public record RiskCloseRequest(@NotBlank(message = "reason 必填") String reason) {
    }

    // ===== M3 降级监控（ADMIN-P2-01/02：矩阵/手动切换/事件时间线） =====

    @GetMapping("/degradation/matrix")
    public ApiResponse<List<Map<String, Object>>> degradationMatrix() {
        return ApiResponse.ok(degradationMatrixService.matrix());
    }

    /** 手动切换（X-Confirm 固定短语 + reason 必填；仅 ops/super，SecurityConfig 强制） */
    @PostMapping("/degradation/{point}/override")
    public ApiResponse<Void> override(@PathVariable String point,
                                      @RequestHeader(value = "X-Confirm", required = false) String confirm,
                                      @Valid @RequestBody DegradationOverrideRequest request) {
        if (!CONFIRM_PHRASE.equals(confirm)) {
            return ApiResponse.error(ErrorCode.PARAM_INVALID.code(), "高危操作需 X-Confirm: CONFIRM 头（操作二次确认）");
        }
        degradationMatrixService.override(point, request.to(), operatorName(), request.reason());
        return ApiResponse.ok(null);
    }

    /** 取消覆盖（回配置默认） */
    @PostMapping("/degradation/{point}/override/cancel")
    public ApiResponse<Void> cancelOverride(@PathVariable String point,
                                            @RequestHeader(value = "X-Confirm", required = false) String confirm,
                                            @Valid @RequestBody RiskCloseRequest request) {
        if (!CONFIRM_PHRASE.equals(confirm)) {
            return ApiResponse.error(ErrorCode.PARAM_INVALID.code(), "高危操作需 X-Confirm: CONFIRM 头（操作二次确认）");
        }
        degradationMatrixService.cancelOverride(point, operatorName(), request.reason());
        return ApiResponse.ok(null);
    }

    @GetMapping("/degradation/events")
    public ApiResponse<List<DegradationEvent>> degradationEvents(
            @RequestParam(required = false) String point,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(degradationMatrixService.events(point, limit));
    }

    public record DegradationOverrideRequest(@NotBlank(message = "to 必填") String to,
                                             @NotBlank(message = "reason 必填") String reason) {
    }

    // ===== M9 知识库平台统计（ADMIN-P2-03） =====

    @GetMapping("/knowledge/stats")
    public ApiResponse<Map<String, Object>> knowledgeStats(@RequestParam(required = false) UUID tenantId) {
        return ApiResponse.ok(knowledgeBaseService.platformStats(tenantId));
    }

    // ===== M10/M12 运营洞察（ADMIN-P2-04/05） =====

    @GetMapping("/insights/channel-stats")
    public ApiResponse<Map<String, Object>> channelStats() {
        return ApiResponse.ok(opsInsightsService.channelStats());
    }

    @GetMapping("/insights/dead-ledger")
    public ApiResponse<List<OpsInsightsService.DeadLedgerEntry>> deadLedger(@RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(opsInsightsService.deadLedger(limit));
    }

    @GetMapping("/insights/quality-trend")
    public ApiResponse<Map<String, Object>> qualityTrend() {
        return ApiResponse.ok(opsInsightsService.qualityTrend());
    }

    @GetMapping("/insights/alert-funnel")
    public ApiResponse<Map<String, Object>> alertFunnel() {
        return ApiResponse.ok(opsInsightsService.alertFunnel());
    }

    @GetMapping("/insights/tenant-health")
    public ApiResponse<List<Map<String, Object>>> tenantHealth() {
        return ApiResponse.ok(opsInsightsService.tenantHealth());
    }
}
