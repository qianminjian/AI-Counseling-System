package com.mindsafe.ai.safety;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 危机热线单一事实源（DOC-073 B1，doing/77 §22）
 * <p>
 * 所有热线话术拼装点（Layer1 硬拦截 / RED 硬短路 / Layer2 召回 / 热线显示文本 /
 * 时长引导）统一经本 Provider 渲染：话术模板保留预审核硬编码（防 LLM 幻觉，
 * 遵 design/14 铁律），仅 {@link #PLACEHOLDER} 占位符由配置注入的号码替换。
 * <p>
 * 配置：`mindsafe.safety.crisis-hotline`（环境变量 MINDSAFE_CRISIS_HOTLINE 可覆盖），
 * 缺省回退 {@link CrisisResources#NATIONAL_PSYCHOLOGICAL_AID}——安全组件不允许
 * fail-fast 阻断危机响应（失败安全）。
 */
@Component
public class CrisisHotlineProvider {

    /** 模板占位符：话术中需渲染热线号码的位置 */
    public static final String PLACEHOLDER = "{hotline}";

    /** 缺省热线（兜底常量，引用 CrisisResources 权威定义，字面量仅存一处） */
    public static final String DEFAULT_HOTLINE = CrisisResources.NATIONAL_PSYCHOLOGICAL_AID;

    private final String hotline;

    /** 缺省路径（测试/无配置场景）：使用兜底常量 */
    public CrisisHotlineProvider() {
        this(DEFAULT_HOTLINE);
    }

    @Autowired
    public CrisisHotlineProvider(@Value("${mindsafe.safety.crisis-hotline:" + DEFAULT_HOTLINE + "}") String hotline) {
        this.hotline = hotline;
    }

    /** 当前生效热线号码 */
    public String hotline() {
        return hotline;
    }

    /** 渲染话术模板：替换 {@link #PLACEHOLDER} 占位符（无占位符时原样返回） */
    public String render(String template) {
        return template.replace(PLACEHOLDER, hotline);
    }
}
