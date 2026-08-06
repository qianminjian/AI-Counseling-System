package com.mindsafe.service.conversation;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会话级个人信息提取器（ARCH-001 C1：从 ConversationServiceImpl 拆出的纯函数深模块）。
 * <p>
 * 4 组正则（真实名字/年龄/年级/班级），零 LLM、无状态、无副作用；
 * 原实现为 ConversationServiceImpl 私有方法不可独立测试，本类收敛后获得完整测试面。
 * 提取结果由调用方写入 SessionState.personalInfo（会话结束即销毁）。
 */
@Component
public class PersonalInfoExtractor {

    /** 名字提取：我叫XX / 我的名字是XX / 你可以叫我XX */
    private static final Pattern NAME_PATTERN = Pattern.compile(
            "(?:我叫|我的名字是?|你可以叫我|我名字是|叫我)([\\u4e00-\\u9fa5a-zA-Z]{1,6})");
    /** 年龄提取：我X岁 / 我今年X岁 */
    private static final Pattern AGE_PATTERN = Pattern.compile(
            "我(?:今年)?(\\d{1,2})\\s*岁");
    /** 年级提取：我在X年级 / 我上X年级 / 我读X年级 */
    private static final Pattern GRADE_PATTERN = Pattern.compile(
            "我(?:在|上|读|是)([一二三四五六1-6])\\s*年级");
    /** 班级提取：我在X班 / 我是X班的 */
    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "我(?:在|是)([\\u4e00-\\u9fa5a-zA-Z0-9]{1,6}班)");

    /** 常见语气词/动词误匹配过滤（命中则忽略该字段） */
    private static final String NAME_STOP_WORDS =
            "(?:是|的|了|吗|呢|吧|啊|哦|哈|嗯|不|没|在|有|要|会|能|可以|知道)";

    /** 提取结果：未命中字段为 null；内容过短或无任何匹配时为 null */
    public record ExtractedInfo(String realName, String age, String grade, String className) {
    }

    /**
     * 从学生消息中提取个人信息（真实名字/年龄/年级/班级）。
     *
     * @param content 学生消息原文
     * @return 提取结果；内容为 null 或长度 &lt; 2 时返回 null
     */
    public ExtractedInfo extract(String content) {
        if (content == null || content.length() < 2) {
            return null;
        }

        String realName = null;
        Matcher m = NAME_PATTERN.matcher(content);
        if (m.find()) {
            String name = m.group(1);
            // 过滤常见误匹配（语气词/动词）
            if (!name.matches(NAME_STOP_WORDS)) {
                realName = name;
            }
        }

        String age = null;
        m = AGE_PATTERN.matcher(content);
        if (m.find()) {
            age = m.group(1) + "岁";
        }

        String grade = null;
        m = GRADE_PATTERN.matcher(content);
        if (m.find()) {
            grade = m.group(1) + "年级";
        }

        String className = null;
        m = CLASS_PATTERN.matcher(content);
        if (m.find()) {
            className = m.group(1);
        }

        return new ExtractedInfo(realName, age, grade, className);
    }
}
