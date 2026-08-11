# frozen/92 前端 DTO 生成器管线独立专题（R-006 归口）

> 创建：2026-08-11 | 来源：doing/92 第五轮架构深化候选清单 R-006（teacher-web DTO 手工镜像后端 VO）
> 背景：**项目负责人按建议执行（2026-08-11）：暂缓项统一冻结独立专题跟踪，不再本专题跟踪**——
> 根因 = teacher-web api.ts 20+ DTO 手工镜像后端 VO + getStudentRadar 返回 Record<string, any>，
> 类型级漂移风险持续；openapi-snapshot 管线仅后端快照（gen-openapi-snapshot.sh），前端无生成端
> （92 原述"student-h5 用法成熟可复用"经核实不成立——student-h5 仅 __contract__ 契约测试，无生成器）。
> 关联：doing/92（来源专题，R-006 ❄️ 冻结移交）；scripts/gen-openapi-snapshot.sh；
> teacher-web apiContract 测试（当前契约防线，勿删）。

## 一、跟踪项

| 归口项 | 来源 | 现状 | 解冻条件 |
|---|---|---|---|
| **R-006 teacher-web DTO 生成管线**（openapi-typescript 接入或等价生成器 + 20+ DTO 迁移 + getStudentRadar 类型化） | doing/92 R-006 | api.ts L83-201 手工镜像 + getStudentRadar Record<string, any>；apiContract 测试兜底端点契约 | 独立批次立项（生成器选型 + 三端统一评估）；或前端专门批次（与 R-001/R-003）合并立项 |

## 二、根因说明（为什么不能顺手完成）

- **无现成生成端**：gen-openapi-snapshot.sh 只产出后端快照，student-h5/teacher-web/parent-h5 均无 DTO 生成器接入——需从零建立生成管线（选型 + 契约文件消费 + 生成物入仓策略）
- **跨端影响**：三端 DTO 生成应统一评估（避免三端各建一套生成器），涉及构建链改动
- **现有防线可用**：apiContract 测试（端点常量表 + 消费面双向校验）已兜底路径/方法级契约，类型漂移为渐进风险非故障

## 三、解冻流程

以下任一条件成立时由项目负责人发起解冻：
1. 前端专门批次（R-001/R-003 端点登记）立项时合并评估
2. DTO 漂移引发实际类型错误（apiContract 无法覆盖的类型级故障）
3. 后端 API 面大版本变更（openapi 快照契约版本升级）

## 四、关联跟踪

- **apiContract 测试**（已实施）：端点/方法级契约防线——管线落地前是唯一兜底，勿删
- **gen-openapi-snapshot.sh**（已实施）：后端 openapi 快照生成——管线接入时的契约输入
- **R-001/R-003**（doing/92 排期）：前端端点登记与请求工厂单例——与 DTO 生成同批评估最优
