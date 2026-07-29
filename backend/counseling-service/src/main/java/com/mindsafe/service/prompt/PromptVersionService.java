package com.mindsafe.service.prompt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.ai.prompt.PromptTemplateService;
import com.mindsafe.domain.entity.PromptVersion;
import com.mindsafe.domain.mapper.PromptVersionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    /** 本地缓存：避免每次对话都查 DB（key = tenantId:templateKey:abGroup） */
    private final Map<String, CachedPrompt> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 分钟

    public PromptVersionService(PromptVersionMapper promptVersionMapper,
                                PromptTemplateService promptTemplateService) {
        this.promptVersionMapper = promptVersionMapper;
        this.promptTemplateService = promptTemplateService;
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
        int hash = Math.abs(studentUserId.hashCode());
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

    /** 激活版本（同 templateKey+abGroup 下只能有一个 active） */
    public void activateVersion(UUID versionId) {
        PromptVersion target = promptVersionMapper.selectById(versionId);
        if (target == null) {
            throw new IllegalArgumentException("版本不存在: " + versionId);
        }
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
