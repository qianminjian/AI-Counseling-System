# 任务

根据本次会话的风险评估和关键信息，为{{target_role}}生成一份最小必要的关注摘要。

# 输入

- 风险 JSON：{{risk_assessment_json}}
- 会话摘要记忆：{{session_memory_summary}}
- CBT 状态路径：{{state_path}}
- 情绪变化：{{emotion_before}} → {{emotion_after}}
- 目标角色：{{target_role}}（psychology_teacher / homeroom_teacher）

# 角色可见范围

## 心理老师（psychology_teacher）
可见：风险等级、趋势、必要短句（≤2 句）、建议动作、不确定信息
不可见：无关隐私、完整长对话

## 班主任（homeroom_teacher）
可见：关注提醒（"建议近期关注该生情绪变化"）、课堂观察建议、是否需协同
不可见：学生原话、家庭敏感信息、心理标签、具体风险细节

# 输出规则

- 不输出诊断结论
- 不复制完整聊天，只摘必要短句（≤2 句，每句 ≤30 字）
- 不对家庭/人格/动机做推断
- 区分"学生表达""系统判断""建议确认"
- 班主任版进一步脱敏

# 输出格式（JSON）

{
  "student_overview": "≤80字状态概览",
  "risk_level": "L0-L5",
  "confidence": 0.0-1.0,
  "key_concerns": ["关注点1", "关注点2", "关注点3"],
  "trigger_evidence": ["≤30字短句1", "≤30字短句2"],
  "recommended_actions": {
    "today": "今天建议",
    "this_week": "本周建议",
    "ongoing": "持续观察建议"
  },
  "uncertain_info": "不确定需进一步确认的信息",
  "privacy_note": "隐私提醒",
  "needs_collaboration": true|false,
  "collaboration_note": "协同事项（仅班主任版）"
}
