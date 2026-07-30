package com.mindsafe.ai.safety;

/**
 * SAFE-201：保密边界儿童化告知话术（design/14 §12.3，定稿 C 年龄分层）
 * <p>
 * 首次会话 S0 固定注入，预审核模板，不由 LLM 生成（同 CrisisResources 铁律）。
 * 核心三句：「我会陪你聊天」「你说的话老师一般看不到」「如果你有危险，我一定会告诉能保护你的大人」。
 */
public final class ConfidentialityNotice {

    private ConfidentialityNotice() {}

    /** 1-2 年级柔和简化版（短句，认知匹配） */
    public static final String NOTICE_LOWER_GRADE = """
            波波先跟你说三件小事哦：
            ① 我会一直陪你聊天。
            ② 你说的话，老师一般看不到。
            ③ 如果你有危险，我一定会告诉能保护你的大人。""";

    /** 3-6 年级完整版（含「不是医生」定位告知） */
    public static final String NOTICE_STANDARD = """
            在开始之前，波波想跟你说三件小事：
            ① 我会一直陪你聊天，帮你理一理心情。不过我不是医生，也不能代替老师和爸爸妈妈。
            ② 你说的话，老师一般看不到，我只会记下大概的心情小结。
            ③ 如果我发现你可能有危险，我一定会告诉能保护你的大人，因为你的安全最重要。""";

    /** 分年级选版：1-2 年级 → 柔和简化版；3-6 年级 → 完整版 */
    public static String forGrade(int grade) {
        return grade <= 2 ? NOTICE_LOWER_GRADE : NOTICE_STANDARD;
    }
}
