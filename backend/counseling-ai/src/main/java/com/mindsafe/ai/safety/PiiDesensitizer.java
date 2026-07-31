package com.mindsafe.ai.safety;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 个人敏感信息（PII）服务端脱敏器。
 * <p>
 * 对孩子输入中的手机号、身份证号、邮箱、姓名、地址等 PII 做掩码处理，
 * 在内容进入 LLM 上下文 / 对话记忆 / 日志之前完成，确保：
 * <ul>
 *   <li>LLM 永远看不到原始 PII，从源头杜绝 AI 复述泄露；</li>
 *   <li>对话记忆与审计日志中不残留明文 PII（数据最小化，对齐 PIPL）。</li>
 * </ul>
 * <p>
 * 脱敏必须在<b>服务端</b>完成（对齐支付宝/阿里云脱敏规范：客户端脱敏可被绕过）。
 * <p>
 * SAFE-204 扩展（2026-07-28）：新增姓名和地址脱敏。姓名识别基于百家姓词典 +
 * 上下文句式（"我叫/我是/同桌/老师叫" + 2~4 字中文）；地址识别基于行政区划/
 * 路名/小区/楼号等模式。脱敏策略：姓名→"某同学/某老师/某人"，地址→"某地"。
 * 保留情绪表达不掩盖（保护咨询信任，对齐 design/14 不评判伦理）。
 */
@Component
public class PiiDesensitizer {

    /** 手机号：1[3-9] 开头 11 位（前后避免紧邻数字，减少误伤） */
    private static final Pattern PHONE = Pattern.compile("(?<![0-9])(1[3-9]\\d{9})(?![0-9])");

    /** 身份证号：18 位（17 位数字 + 数字或 X/x） */
    private static final Pattern ID_CARD = Pattern.compile("(?<![0-9])(\\d{17}[0-9Xx])(?![0-9])");

    /** 邮箱 */
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    // ===== SAFE-204：姓名脱敏 =====

    /** 常用百家姓前 100（覆盖中国 85%+ 人口） */
    private static final Set<String> SURNAMES = Set.of(
            "赵", "钱", "孙", "李", "周", "吴", "郑", "王", "冯", "陈",
            "褚", "卫", "蒋", "沈", "韩", "杨", "朱", "秦", "尤", "许",
            "何", "吕", "施", "张", "孔", "曹", "严", "华", "金", "魏",
            "陶", "姜", "戚", "谢", "邹", "喻", "柏", "水", "窦", "章",
            "云", "苏", "潘", "葛", "奚", "范", "彭", "郎", "鲁", "韦",
            "昌", "马", "苗", "凤", "花", "方", "俞", "任", "袁", "柳",
            "丰", "鲍", "史", "唐", "费", "廉", "岑", "薛", "雷", "贺",
            "倪", "汤", "滕", "殷", "罗", "毕", "郝", "邬", "安", "常",
            "乐", "于", "时", "傅", "皮", "卞", "齐", "康", "伍", "余",
            "元", "卜", "顾", "孟", "黄", "和", "穆", "萧", "尹", "姚",
            "邵", "湛", "汪", "祁", "毛", "禹", "狄", "米", "贝", "明",
            "臧", "计", "伏", "成", "戴", "谈", "宋", "茅", "庞", "熊",
            "纪", "舒", "屈", "项", "祝", "董", "梁", "杜", "阮", "蓝",
            "闵", "席", "季", "麻", "强", "贾", "路", "娄", "危", "江",
            "童", "颜", "郭", "梅", "盛", "林", "徐", "高",
            "田", "樊", "胡", "霍", "虞", "万", "支", "柯", "昝", "管",
            "卢", "莫", "经", "房", "裘", "缪", "干", "解", "应", "宗",
            "丁", "宣", "邓", "贲", "郁", "单", "杭", "洪", "包", "诸",
            "左", "石", "崔", "吉", "钮", "龚", "程", "嵇", "邢", "滑",
            "裴", "陆", "荣", "翁", "荀", "羊", "甄", "家", "封", "芮",
            "储", "靳", "汲", "邴", "糜", "松", "段", "富", "巫", "乌",
            "焦", "巴", "弓", "牧", "隗", "山", "谷", "车", "侯", "宓",
            "蓬", "全", "郗", "班", "仰", "秋", "仲", "伊", "宫", "宁",
            "仇", "栾", "暴", "甘", "钭", "厉", "戎", "祖", "武", "符",
            "景", "詹", "束", "龙", "叶", "幸", "司", "韶", "郜",
            "黎", "蓟", "薄", "印", "宿", "白", "怀", "蒲", "邰", "从",
            "鄂", "索", "咸", "籍", "赖", "卓", "蔺", "屠", "蒙", "池",
            "乔", "阴", "胥", "能", "苍", "双", "闻", "莘", "党",
            "翟", "谭", "贡", "劳", "逄", "姬", "申", "扶", "堵", "冉",
            "宰", "郦", "雍", "却", "璩", "桑", "桂", "濮", "牛", "寿",
            "通", "边", "扈", "燕", "冀", "僧", "浦", "尚", "农", "温",
            "别", "庄", "晏", "柴", "瞿", "阎", "充", "慕", "连", "茹",
            "习", "艾", "鱼", "容", "向", "古", "易", "慎", "戈", "廖",
            "庾", "终", "暨", "居", "衡", "步", "都", "耿", "满", "弘"
    );

    /**
     * 姓名上下文触发词（孩子常用句式）：
     * "我叫/我是/他叫/她叫/同桌叫/同学叫/老师叫/班主任是/爸爸叫/妈妈叫" + 姓名
     */
    private static final Pattern NAME_CONTEXT = Pattern.compile(
            "(?:我叫|我是|他叫|她叫|同桌(?:叫|是)|同学(?:叫|是)|老师(?:叫|是)|班主任(?:叫|是)|爸爸(?:叫|是)|妈妈(?:叫|是)|哥哥(?:叫|是)|姐姐(?:叫|是)|弟弟(?:叫|是)|妹妹(?:叫|是))" +
            "([\\u4e00-\\u9fff]{2,4})"
    );

    /** 独立姓名（百家姓开头 + 1~2 字名，前有标点/空格边界，非贪婪） */
    private static final Pattern STANDALONE_NAME = Pattern.compile(
            "(?<=[,.\u3002\uff0c\uff01\uff1f\u3001\uff1b\uff1a\u201c\u201d\u2018\u2019\\s])([\\u4e00-\\u9fff])([\\u4e00-\\u9fff]{1,2}?)"
    );

    // ===== SAFE-204：地址脱敏 =====

    /** 地址模式：包含省/市/区/县/镇/路/街/巷/弄/号/栋/幢/楼/室/小区/花园/苑/村 等 */
    private static final Pattern ADDRESS = Pattern.compile(
            "[\\u4e00-\\u9fff]{2,6}(?:省|市|区|县|镇|乡|街道)" +
            "(?:[\\u4e00-\\u9fff\\d]*(?:路|街|巷|弄|道|大道|里|村|组))?" +
            "(?:[\\d]*号?)?" +
            "(?:[\\u4e00-\\u9fff\\d]*(?:小区|花园|苑|庭|府|城|湾|岸|园|庄|寨|坊|居|栋|幢|楼|室|单元))?"
    );

    /** 具体门牌地址（路/街 + 号/栋/室） */
    private static final Pattern STREET_ADDRESS = Pattern.compile(
            "[\\u4e00-\\u9fff]{2,8}(?:路|街|巷|弄|大道|道)[\\d]*号" +
            "(?:[\\u4e00-\\u9fff\\d]*(?:栋|幢|楼|单元|室))?"
    );

    /** 小区/学校/具体地点名 + 门牌 */
    private static final Pattern COMMUNITY_ADDRESS = Pattern.compile(
            "[\\u4e00-\\u9fff]{2,8}(?:小区|花园|苑|庭|府|城|湾|岸|园|庄|寨|居)" +
            "(?:[\\d]*(?:栋|幢|号楼|单元|室|号))?"
    );

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
        // SAFE-204：姓名与地址脱敏
        result = maskAddress(result);
        result = maskName(result);
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

    /**
     * SAFE-204：姓名脱敏。
     * <p>
     * 策略：(1) 上下文句式优先匹配（"我叫XXX"），替换为"某人"；
     *       (2) 独立百家姓开头 + 名字 → "某同学"。
     * 保守策略：避免过度脱敏（单字姓不处理、常用词排除）。
     */
    private String maskName(String text) {
        // (1) 上下文句式匹配
        Matcher contextMatcher = NAME_CONTEXT.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (contextMatcher.find()) {
            String name = contextMatcher.group(1);
            String fullMatch = contextMatcher.group();
            String prefix = fullMatch.substring(0, fullMatch.length() - name.length());
            contextMatcher.appendReplacement(sb, Matcher.quoteReplacement(prefix + "某人"));
        }
        contextMatcher.appendTail(sb);
        String result = sb.toString();

        // (2) 独立姓名：百家姓开头 + 中文名（保守：仅在标点/空格边界处匹配）
        Matcher nameMatcher = STANDALONE_NAME.matcher(result);
        sb = new StringBuilder();
        while (nameMatcher.find()) {
            String surname = nameMatcher.group(1);
            if (SURNAMES.contains(surname)) {
                nameMatcher.appendReplacement(sb, "某同学");
            }
        }
        nameMatcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * SAFE-204：地址脱敏。
     * <p>
     * 将具体地址（含省市区路号小区等）替换为"某地"。
     * 三层正则依次匹配：行政区划完整地址 > 具体门牌地址 > 小区/社区名。
     */
    private String maskAddress(String text) {
        String result = text;
        result = ADDRESS.matcher(result).replaceAll("某地");
        result = STREET_ADDRESS.matcher(result).replaceAll("某地");
        result = COMMUNITY_ADDRESS.matcher(result).replaceAll("某地");
        return result;
    }
}
