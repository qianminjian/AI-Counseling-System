# AI 小学生心理辅导系统

面向小学的 AI 心理辅导 SaaS 系统：学生端 AI 对话（CBT 流程 + 风险识别）+ 老师后台预警，多学校数据隔离。

## 当前状态

设计文档阶段完成，代码未启动。项目状态与待决问题见 [design/BEACON.md](design/BEACON.md)。

## 目录导航

| 目录 | 内容 |
|------|------|
| [design/](design/) | 正式设计文档（01~15）+ BEACON 项目明灯 |
| [doc/](doc/) | 归档层（原始 docx + 早期需求/探索产物） |
| [scripts/](scripts/) | 工具脚本 |
| [src/](src/) | 源代码（待启动） |
| [tests/](tests/) | 测试（unit / integration / e2e） |
| [tmp/](tmp/) | 临时产物（不追踪） |

## 约定

- 目录结构与使用规则：[STRUCTURE.md](STRUCTURE.md)
- Agent 工作规则：`AGENTS.md` + `.qoder/rules/`（中央库同步，勿改）

## 开发

技术栈待选型（见 BEACON.md 待解决问题）。启动任何服务前遵守端口检查红线（AGENTS.md §6）。
