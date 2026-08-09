# Browser Agent Web 界面自动化遍历测试 · 产出目录

> 起始：2026-08-09 | 来源：doing/82（DOC-085）
> 范围：本目录承载所有 doing/82 §六 场景执行产生的问题清单、截图、汇总报告、修复闭环台账。

## 文件结构

- `ISSUES-学生端.md` / `ISSUES-对话专项.md` / `ISSUES-教师端.md` / `ISSUES-家长端.md` / `ISSUES-联动.md`：各端问题清单
- `ISSUES-SUMMARY.md`：汇总（按端+场景统计）
- `ISSUES-闭环记录.md`：修复 → 部署 → 复测台账
- `screenshots/`：所有失败/参考截图（场景ID-序号.png）
- `_README.md`：本文件

## 问题登记格式（doing/82 §7.2）

```
### BUG-<端>-<场景>-<序号> [P{0-3}] <标题>
- 场景：<场景 ID + 标题>
- 步骤：<第 N 步>
- 期望：...
- 实际：...
- 截图：screenshots/<场景>-<步骤>.png
- 控制台/网络：...
- 疑似根因：...
- 状态：OPEN | 修复提交：- | 复测结果：-
```

状态机：`OPEN → FIXED → VERIFIED → REGRESSION`
