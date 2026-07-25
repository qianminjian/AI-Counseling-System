package com.mindsafe.ai.safety;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 个人敏感信息（PII）服务端脱敏器。
 * <p>
 * 对孩子输入中的手机号、身份证号、邮箱等可结构化识别的 PII 做掩码处理，
 * 在内容进入 LLM 上下文 / 对话记忆 / 日志之前完成，确保：
 * <ul>
 *   <li>LLM 永远看不到原始 PII，从源头杜绝 AI 复述泄露；</li>
 *   <li>对话记忆与审计日志中不残留明文 PII（数据最小化，对齐 PIPL）。</li>
 * </ul>
 * <p>
 * 脱敏必须在<b>服务端</b>完成（对齐支付宝/阿里云脱敏规范：客户端脱敏可被绕过）。
 * <p>
 * 已知限制（MVP）：真实姓名、详细地址难以用正则准确识别，暂不处理；
 * 且本脱敏不掩盖情绪表达（保护咨询信任，对齐 design/14 不评判伦理）。
 */
@Component
public class PiiDesensitizer {

    /** 手机号：1[3-9] 开头 11 位（前后避免紧邻数字，减少误伤） */
    private static final Pattern PHONE = Pattern.compile("(?<![0-9])(1[3-9]\\d{9})(?![0-9])");

    /** 身份证号：18 位（17 位数字 + 数字或 X/x） */
    private static final Pattern ID_CARD = Pattern.compile("(?<![0-9])(\\d{17}[0-9Xx])(?![0-9])");

    /** 邮箱 */
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /**
     * 对文本中的 PII 做脱敏，返回掩码后的文本。
     *
     * @param text 原始文本（孩子输入）
     * @return 脱敏后文本；入参为 null 时原样返回
     */
    public String desensitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        result = maskPhone(result);
        result = maskIdCard(result);
        result = maskEmail(result);
        return result;
    }

    /** 手机号掩码：保留前 3 后 2，如 138****34 */
    private String maskPhone(String text) {
        Matcher m = PHONE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String phone = m.group(1);
            m.appendReplacement(sb, phone.substring(0, 3) + "****" + phone.substring(9));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 身份证掩码：保留前 3 后 2，如 110****56 */
    private String maskIdCard(String text) {
        Matcher m = ID_CARD.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String id = m.group(1);
            m.appendReplacement(sb, id.substring(0, 3) + "*************" + id.substring(16));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 邮箱掩码：本地名保留首字符，如 t***@example.com */
    private String maskEmail(String text) {
        Matcher m = EMAIL.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String email = m.group();
            int at = email.indexOf('@');
            String local = email.substring(0, at);
            String maskedLocal = local.length() <= 1 ? local : local.charAt(0) + "***";
            m.appendReplacement(sb, Matcher.quoteReplacement(maskedLocal + email.substring(at)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
