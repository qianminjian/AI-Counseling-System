package com.mindsafe.service.prompt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.ai.prompt.PromptTemplateService;
import com.mindsafe.domain.entity.PromptVersion;
import com.mindsafe.domain.mapper.PromptVersionMapper;
import com.mindsafe.service.audit.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 版本管理服务（AI-005）
 * <p>
 * 核心逻辑：
 * 1. DB 优先：查找 tenant 级 → 全局级生效版本
 * 2. Classpath 降级：DB 无记录时回退到 PromptTemplateService 的 .md 文件
 * 3. A/B 路由：按 studentUserId hash 决定分组（control / treatment_a）
 * 4. 版本标记：返回 versionTag 供会话记录，用于效果对比
 */
@Service
public class PromptVersionService {

    private static final Logger log = LoggerFactory.getLogger(PromptVersionService.class);

    /** 模板 key → classpath 路径映射（复用 PromptTemplateService 常量） */
    private static final Map<String, String> KEY_TO_CLASSPATH = Map.ofEntries(
            Map.entry("SYS_001", PromptTemplateService.SYS_001),
            Map.entry("SAF_001", PromptTemplateService.SAF_001),
            Map.entry("SAF_002", PromptTemplateService.SAF_002),
            Map.entry("LANG_001", PromptTemplateService.LANG_001),
            Map.entry("LANG_002", PromptTemplateService.LANG_002),
            Map.entry("LANG_003", PromptTemplateService.LANG_003),
            Map.entry("SKL_001", PromptTemplateService.SKL_001),
            Map.entry("SKL_002", PromptTemplateService.SKL_002),
            Map.entry("SKL_003", PromptTemplateService.SKL_003),
            Map.entry("TSK_001", PromptTemplateService.TSK_001),
            Map.entry("TSK_002", PromptTemplateService.TSK_002),
            Map.entry("TSK_003", PromptTemplateService.TSK_003),
            Map.entry("TSK_004", PromptTemplateService.TSK_004),
            Map.entry("EMO_001", PromptTemplateService.EMO_001)
    );

    private final PromptVersionMapper promptVersionMapper;
    private final PromptTemplateService promptTemplateService;
    private final RedTeamRegressionRunner redTeamRegressionRunner;
    private final TemplateMatrixRegistry templateMatrixRegistry;
    private final AuditLogService auditLogService;
    private final PromptEvalScoreReader evalScoreReader;

    /** 本地缓存：避免每次对话都查 DB（key = tenantId:templateKey:abGroup） */
    private final Map<String, CachedPrompt> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 分钟

    public PromptVersionService(PromptVersionMapper promptVersionMapper,
                                PromptTemplateService promptTemplateService,
                                RedTeamRegressionRunner redTeamRegressionRunner,
                                TemplateMatrixRegistry templateMatrixRegistry,
                                AuditLogService auditLogService,
                                PromptEvalScoreReader evalScoreReader) {
        this.promptVersionMapper = promptVersionMapper;
        this.promptTemplateService = promptTemplateService;
        this.redTeamRegressionRunner = redTeamRegressionRunner;
        this.templateMatrixRegistry = templateMatrixRegistry;
        this.auditLogService = auditLogService;
        this.evalScoreReader = evalScoreReader;
    }

    /**
     * 解析 Prompt 模板（A/B 路由 + DB 优先 + classpath 降级）
     *
     * @param tenantId      租户 ID
     * @param templateKey   模板标识（如 SYS_001）
     * @param studentUserId 学生 ID（用于 A/B 分组 hash）
     * @param variables     模板变量
     * @return 解析结果（渲染后内容 + 版本标记）
     */
    public ResolvedPrompt resolve(UUID tenantId, String templateKey, UUID studentUserId,
                                  Map<String, String> variables) {
        String abGroup = assignAbGroup(studentUserId);
        String content = loadContent(tenantId, templateKey, abGroup);
        String versionTag = buildVersionTag(tenantId, templateKey, abGroup);

        // 渲染变量
        String rendered = content;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}",
                    entry.getValue() != null ? entry.getValue() : "");
        }

        return new ResolvedPrompt(rendered, versionTag, abGroup);
    }

    /**
     * 仅获取原始模板（不渲染变量），用于语言模板等场景
     */
    public ResolvedPrompt resolveRaw(UUID tenantId, String templateKey, UUID studentUserId) {
        String abGroup = assignAbGroup(studentUserId);
        String content = loadContent(tenantId, templateKey, abGroup);
        String versionTag = buildVersionTag(tenantId, templateKey, abGroup);
        return new ResolvedPrompt(content, versionTag, abGroup);
    }

    /**
     * A/B 分组路由：按 studentUserId 的 hashCode 取模
     * - 80% control（对照组）
     * - 20% treatment_a（实验组）
     * 同一学生始终在同一组（确定性 hash）
     */
    public String assignAbGroup(UUID studentUserId) {
        if (studentUserId == null) return "control";
        // 位掩码取非负（Math.abs(Integer.MIN_VALUE) 仍为负数，会导致取模结果偏移）
        int hash = studentUserId.hashCode() & Integer.MAX_VALUE;
        return (hash % 10) < 2 ? "treatment_a" : "control";
    }

    /**
     * 加载模板内容：DB 优先（tenant 级 → 全局级）→ classpath 降级
     */
    private String loadContent(UUID tenantId, String templateKey, String abGroup) {
        String cacheKey = tenantId + ":" + templateKey + ":" + abGroup;
        CachedPrompt cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.content;
        }

        // 1. 查 DB：tenant 级生效版本
        PromptVersion pv = findActiveVersion(tenantId, templateKey, abGroup);
        if (pv == null && tenantId != null) {
            // 2. 查 DB：全局级生效版本（tenant_id IS NULL）
            pv = findActiveVersion(null, templateKey, abGroup);
        }
        // 2.5 如果实验组没有配置，降级到 control 组
        if (pv == null && !"control".equals(abGroup)) {
            pv = findActiveVersion(tenantId, templateKey, "control");
            if (pv == null && tenantId != null) {
                pv = findActiveVersion(null, templateKey, "control");
            }
        }

        String content;
        if (pv != null) {
            content = pv.getContent();
            log.debug("Prompt 从 DB 加载: key={}, version={}, abGroup={}", templateKey, pv.getVersion(), abGroup);
        } else {
            // 3. Classpath 降级
            String classpathPath = KEY_TO_CLASSPATH.get(templateKey);
            if (classpathPath == null) {
                throw new IllegalArgumentException("未知模板标识: " + templateKey);
            }
            content = promptTemplateService.getTemplate(classpathPath);
            log.debug("Prompt 从 classpath 降级加载: key={}, path={}", templateKey, classpathPath);
        }

        cache.put(cacheKey, new CachedPrompt(content));
        return content;
    }

    private PromptVersion findActiveVersion(UUID tenantId, String templateKey, String abGroup) {
        LambdaQueryWrapper<PromptVersion> wrapper = new LambdaQueryWrapper<PromptVersion>()
                .eq(PromptVersion::getTemplateKey, templateKey)
                .eq(PromptVersion::getAbGroup, abGroup)
                .eq(PromptVersion::getIsActive, true)
                .last("LIMIT 1");
        if (tenantId != null) {
            wrapper.eq(PromptVersion::getTenantId, tenantId);
        } else {
            wrapper.isNull(PromptVersion::getTenantId);
        }
        return promptVersionMapper.selectOne(wrapper);
    }

    private String buildVersionTag(UUID tenantId, String templateKey, String abGroup) {
        String cacheKey = tenantId + ":" + templateKey + ":" + abGroup + ":tag";
        CachedPrompt cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.content;
        }

        PromptVersion pv = findActiveVersion(tenantId, templateKey, abGroup);
        if (pv == null && tenantId != null) {
            pv = findActiveVersion(null, templateKey, abGroup);
        }
        String tag;
        if (pv != null) {
            tag = pv.versionTag();
        } else {
            tag = templateKey + ":v0:classpath";
        }
        cache.put(cacheKey, new CachedPrompt(tag));
        return tag;
    }

    /** 清除缓存（版本切换后调用） */
    public void invalidateCache() {
        cache.clear();
        log.info("Prompt 版本缓存已清除");
    }

    // ===== 管理方法 =====

    /** 创建新版本 */
    public PromptVersion createVersion(UUID tenantId, String templateKey, String content,
                                       String description, String abGroup, UUID createdBy) {
        // 计算下一个版本号
        int nextVersion = getNextVersion(tenantId, templateKey, abGroup);
        PromptVersion pv = PromptVersion.create(tenantId, templateKey, nextVersion, content, description, abGroup, createdBy);
        promptVersionMapper.insert(pv);
        log.info("Prompt 版本已创建: key={}, version={}, abGroup={}", templateKey, nextVersion, abGroup);
        return pv;
    }

    /**
     * 发布门禁激活（G-1 硬化，design/45 §6.1 三门禁 + §7.3 红队回归门禁）
     * <ul>
     *   <li>门禁一：安全关键模板必过红队静态回归（{@link RedTeamRegressionRunner}）</li>
     *   <li>门禁二：审校人签字（reviewer 非空）</li>
     *   <li>门禁三：eval 分数不回退 —— <b>服务端从库读数</b>（{@link PromptEvalScoreReader}），
     *       不接受调用方自报；基线样本充足时目标样本不足同样拒绝</li>
     * </ul>
     * 全部通过才写库，并经 audit_logs 留痕（免 schema 变更）。
     * 无单参绕过口：这是唯一的激活入口。
     *
     * @param versionId 目标版本 ID
     * @param reviewer  审校人（签字留痕，空白拒绝）
     */
    @Transactional
    public void activateVersion(UUID versionId, String reviewer) {
        PromptVersion target = promptVersionMapper.selectById(versionId);
        if (target == null) {
            throw new IllegalArgumentException("版本不存在: " + versionId);
        }

        // 门禁一：安全关键模板红队静态回归
        boolean redTeamPassed = true;
        List<String> redTeamViolations = List.of();
        if (redTeamRegressionRunner.isSafetyCritical(target.getTemplateKey())) {
            RedTeamRegressionRunner.RegressionResult result =
                    redTeamRegressionRunner.run(target.getTemplateKey(), target.getContent());
            redTeamPassed = result.passed();
            redTeamViolations = result.violations();
        }

        // 门禁三硬化：eval 分数服务端从库读数（counseling_sessions.prompt_version + quality_scores）
        PromptEvalScoreReader.EvalStat newStat = evalScoreReader.read(target.versionTag());
        PromptVersion baseline = findBaseline(target);
        List<String> evalFailures = new java.util.ArrayList<>();
        double newScore = newStat.overallScore();
        double baselineScore = newScore; // 无有效基线时视为不回退
        String evalNote = "首次激活或基线无有效样本，跳过 eval 对比";
        if (baseline != null) {
            PromptEvalScoreReader.EvalStat baseStat = evalScoreReader.read(baseline.versionTag());
            if (baseStat.scoredCount() >= PromptEvalScoreReader.MIN_EVAL_SAMPLES) {
                baselineScore = baseStat.overallScore();
                evalNote = String.format("eval=%.3f/%.3f（样本 %d/%d）",
                        newStat.overallScore(), baseStat.overallScore(),
                        newStat.scoredCount(), baseStat.scoredCount());
                if (newStat.scoredCount() < PromptEvalScoreReader.MIN_EVAL_SAMPLES) {
                    evalFailures.add(String.format("eval 数据不足: 目标版本仅 %d 条评分样本（至少 %d 条）",
                            newStat.scoredCount(), PromptEvalScoreReader.MIN_EVAL_SAMPLES));
                } else if (newStat.overallScore() < baseStat.overallScore()) {
                    evalFailures.add(String.format("eval 分数回退：%.3f < 基线 %.3f",
                            newStat.overallScore(), baseStat.overallScore()));
                }
            }
        }

        // 门禁二 + 三：审校签字 + eval 不回退（复用矩阵注册表的统一门禁判定）
        TemplateMatrixRegistry.GateResult gate =
                templateMatrixRegistry.checkReleaseGate(redTeamPassed, reviewer, newScore, baselineScore);
        if (!gate.passed() || !evalFailures.isEmpty()) {
            // 失败明细逐条展开（审计可读）：红队违规明细替换通用文案，eval 明细用从库读数的精确值
            List<String> failures = new java.util.ArrayList<>(gate.failures());
            failures.remove("红队护栏用例未全部通过");
            failures.removeIf(f -> f.startsWith("eval 分数回退"));
            failures.addAll(evalFailures);
            for (String v : redTeamViolations) {
                failures.add("红队回归违规: " + v);
            }
            String detail = String.join("; ", failures);
            log.warn("发布门禁拒绝激活: key={}, version={}, 失败项: {}",
                    target.getTemplateKey(), target.getVersion(), detail);
            throw new IllegalStateException("发布门禁未通过: " + detail);
        }

        doActivate(target);

        // 审批留痕（audit_logs，免 schema 变更）
        auditLogService.log(target.getTenantId(), target.getCreatedBy(),
                "PROMPT_VERSION_ACTIVATE", "prompt_version", versionId,
                String.format("key=%s, version=%d, reviewer=%s, redteam_pass=%s, %s",
                        target.getTemplateKey(), target.getVersion(), reviewer,
                        redTeamPassed, evalNote));
    }

    /** 查同组当前生效版本（基线），排除目标自身 */
    private PromptVersion findBaseline(PromptVersion target) {
        List<PromptVersion> actives = promptVersionMapper.selectList(
                new LambdaQueryWrapper<PromptVersion>()
                        .eq(PromptVersion::getTemplateKey, target.getTemplateKey())
                        .eq(PromptVersion::getAbGroup, target.getAbGroup())
                        .eq(PromptVersion::getIsActive, true)
                        .eq(target.getTenantId() != null, PromptVersion::getTenantId, target.getTenantId())
                        .isNull(target.getTenantId() == null, PromptVersion::getTenantId)
        );
        return actives.stream()
                .filter(v -> !v.getVersionId().equals(target.getVersionId()))
                .findFirst()
                .orElse(null);
    }

    /** 停用同组旧版本 + 激活目标 + 缓存失效（互斥铁律） */
    private void doActivate(PromptVersion target) {
        // 停用同组其他版本
        List<PromptVersion> actives = promptVersionMapper.selectList(
                new LambdaQueryWrapper<PromptVersion>()
                        .eq(PromptVersion::getTemplateKey, target.getTemplateKey())
                        .eq(PromptVersion::getAbGroup, target.getAbGroup())
                        .eq(PromptVersion::getIsActive, true)
                        .eq(target.getTenantId() != null, PromptVersion::getTenantId, target.getTenantId())
                        .isNull(target.getTenantId() == null, PromptVersion::getTenantId)
        );
        for (PromptVersion old : actives) {
            old.setIsActive(false);
            old.setUpdatedAt(Instant.now());
            promptVersionMapper.updateById(old);
        }
        // 激活目标
        target.setIsActive(true);
        target.setUpdatedAt(Instant.now());
        promptVersionMapper.updateById(target);
        invalidateCache();
        log.info("Prompt 版本已激活: key={}, version={}, abGroup={}",
                target.getTemplateKey(), target.getVersion(), target.getAbGroup());
    }

    /** 查询版本列表 */
    public List<PromptVersion> listVersions(UUID tenantId, String templateKey) {
        LambdaQueryWrapper<PromptVersion> wrapper = new LambdaQueryWrapper<PromptVersion>()
                .eq(PromptVersion::getTemplateKey, templateKey)
                .orderByDesc(PromptVersion::getVersion);
        if (tenantId != null) {
            wrapper.and(w -> w.eq(PromptVersion::getTenantId, tenantId).or().isNull(PromptVersion::getTenantId));
        }
        return promptVersionMapper.selectList(wrapper);
    }

    private int getNextVersion(UUID tenantId, String templateKey, String abGroup) {
        LambdaQueryWrapper<PromptVersion> wrapper = new LambdaQueryWrapper<PromptVersion>()
                .eq(PromptVersion::getTemplateKey, templateKey)
                .eq(PromptVersion::getAbGroup, abGroup)
                .orderByDesc(PromptVersion::getVersion)
                .last("LIMIT 1");
        if (tenantId != null) {
            wrapper.eq(PromptVersion::getTenantId, tenantId);
        } else {
            wrapper.isNull(PromptVersion::getTenantId);
        }
        PromptVersion latest = promptVersionMapper.selectOne(wrapper);
        return latest != null ? latest.getVersion() + 1 : 1;
    }

    // ===== 内部类 =====

    /** 解析结果 */
    public record ResolvedPrompt(String content, String versionTag, String abGroup) {
    }

    /** 缓存条目 */
    private static class CachedPrompt {
        final String content;
        final long createdAt;

        CachedPrompt(String content) {
            this.content = content;
            this.createdAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > CACHE_TTL_MS;
        }
    }
}
