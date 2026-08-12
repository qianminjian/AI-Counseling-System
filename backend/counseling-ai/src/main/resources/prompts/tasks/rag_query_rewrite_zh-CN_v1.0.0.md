# 任务

将学生的问题改写为适合知识库检索的查询。

# 输入

- 学生原始问题：{{student_question}}
- 当前风险等级：{{risk_level}}
- 场景标签：{{scenario_id}}

# 改写规则

1. 如果 risk_level >= L4：safety_first=true，只检索"求助路径/危机支持/学校流程"，不检索心理教育内容。
2. 不生成危险方法查询（自伤方法/药物剂量/暴力手段）。
3. 不把隐私细节写入查询，只保留主题词。
4. 只检索经审核的校园心理知识库，不检索开放互联网。
5. 查询用中文，简洁，≤50 字。

# 输出格式（JSON）

{
  "safety_first": true|false,
  "query": "改写后的检索查询",
  "knowledge_scope": ["emotion_management", "coping_skills", "help_seeking", "school_process"],
  "excluded_sensitive_details": ["被排除的敏感细节"]
}
