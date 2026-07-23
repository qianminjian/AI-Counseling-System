---
kind: logging_system
name: 日志系统：尚未实现（仅脚本级 console.log/print）
category: logging_system
scope:
    - '**'
source_files:
    - .gitignore
---

本仓库目前处于设计文档与脚手架阶段，src/ 目录为空，未包含任何业务代码。全仓搜索未发现任何结构化日志框架、日志级别管理或统一 logger 模块的踪迹；所有“日志”输出均来自 scripts/ 下的文档生成脚本中的 `console.log()` / `print()` 调试语句，且 `.gitignore` 中仅有一行 `*.log` 用于忽略本地日志文件。

因此，本项目当前不存在可识别的 logging_system 架构或约定，该类别对本仓库不适用。