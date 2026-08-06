package com.mindsafe.ai.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 输出敏感词库（Layer1 实时硬过滤的词库底座）。
 * <p>
 * 启动时从 classpath {@code safety/output-sensitive-keywords.json} 加载并预编译，
 * 供 {@link OutputContentFilter} 在流式输出过程中做滑动窗口匹配。
 * <p>
 * 设计原则（对齐 design/14 儿童安全对话规范 + TC260 内容安全要求）：
 * <ul>
 *   <li>词库保守：仅收录「AI 绝不应说出」的高置信<b>多字短语</b>（block 级），
 *       避免误伤 AI 在危机干预中对孩子原话的共情性复述；</li>
 *   <li>block 级：流式实时拦截（命中即中断输出并替换为安全话术）；</li>
 *   <li>flag 级：不实时拦截，仅作为 Layer2 语义审查的关注类目（预留扩展）。</li>
 * </ul>
 */
@Component
public class SafetyKeywordLibrary {

    private static final Logger log = LoggerFactory.getLogger(SafetyKeywordLibrary.class);

    private static final String RESOURCE_PATH = "/safety/output-sensitive-keywords.json";
    private static final String LEVEL_BLOCK = "block";

    // ARCH-010 P2-2：注入唯一 ObjectMapper（此前 load() 内每调用 new，配置不统一）
    private final ObjectMapper objectMapper;

    public SafetyKeywordLibrary(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** block 级规则（参与 Layer1 实时拦截） */
    private final List<CategoryRule> blockRules = new ArrayList<>();

    /** flag 级规则（预留：Layer2 语义审查关注类目，不参与实时拦截） */
    private final List<CategoryRule> flagRules = new ArrayList<>();

    /** 最长关键词长度（决定 OutputContentFilter 滑动窗口大小） */
    private int maxKeywordLength = 0;

    /** 词库分类规则 */
    public record CategoryRule(String name, String label, String level, List<String> keywords) {}

    /** 关键词命中结果 */
    public record KeywordHit(String category, String categoryLabel, String keyword, String level) {}

    @PostConstruct
    void load() {
        try (InputStream in = getClass().getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                log.error("输出敏感词库资源不存在: {}，Layer1 过滤将不生效", RESOURCE_PATH);
                return;
            }
            JsonNode root = objectMapper.readTree(in);
            JsonNode categories = root.get("categories");
            if (categories == null || !categories.isArray()) {
                log.error("输出敏感词库格式错误（缺少 categories 数组）: {}", RESOURCE_PATH);
                return;
            }
            for (JsonNode cat : categories) {
                String name = text(cat, "name");
                String label = cat.has("label") ? text(cat, "label") : name;
                String level = text(cat, "level");
                List<String> keywords = new ArrayList<>();
                if (cat.has("keywords") && cat.get("keywords").isArray()) {
                    cat.get("keywords").forEach(k -> {
                        String kw = k.asText();
                        if (!kw.isBlank()) {
                            keywords.add(kw);
                        }
                    });
                }
                CategoryRule rule = new CategoryRule(name, label, level, List.copyOf(keywords));
                if (LEVEL_BLOCK.equalsIgnoreCase(level)) {
                    blockRules.add(rule);
                } else {
                    flagRules.add(rule);
                }
                for (String kw : keywords) {
                    maxKeywordLength = Math.max(maxKeywordLength, kw.length());
                }
            }
            int totalBlock = blockRules.stream().mapToInt(r -> r.keywords().size()).sum();
            log.info("输出敏感词库加载完成: block 类目={}, block 关键词={}, flag 类目={}, 最长关键词={}",
                    blockRules.size(), totalBlock, flagRules.size(), maxKeywordLength);
        } catch (Exception e) {
            log.error("输出敏感词库加载失败: {}", RESOURCE_PATH, e);
        }
    }

    /**
     * 在文本中匹配 block 级关键词（Layer1 实时拦截用）。
     *
     * @param text 待检测文本（通常为滑动窗口内容）
     * @return 首个命中的 block 级关键词；无命中返回 null
     */
    public KeywordHit matchBlock(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (CategoryRule rule : blockRules) {
            for (String keyword : rule.keywords()) {
                if (text.contains(keyword)) {
                    return new KeywordHit(rule.name(), rule.label(), keyword, LEVEL_BLOCK);
                }
            }
        }
        return null;
    }

    /** 最长关键词长度（滑动窗口大小依据）；词库为空时返回默认值 */
    public int maxKeywordLength() {
        return maxKeywordLength > 0 ? maxKeywordLength : 12;
    }

    /** block 级规则数量（测试/监控用） */
    public int blockRuleCount() {
        return blockRules.size();
    }

    private static String text(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : "";
    }
}
