# browser-bfs —— 四端公开面 BFS 遍历工具（99-1/99-2 重构，2026-08-14）

广度优先遍历四端 Web 公开面（只读：非提交类控件可点击），逐步截图 + 状态去重防循环。

## 结构（引擎/适配层解耦）

| 文件 | 职责 |
|------|------|
| `public-surface-bfs.mjs` | 入口：环境解析 + 端侧编排 + 报告汇总（~60 行） |
| `bfs-engine.mjs` | **纯 BFS 引擎**：visited 去重 / 深度·步数限制 / 错误收集（可注入假适配器单测） |
| `cli-adapter.mjs` | **agent-browser CLI 适配 seam**：进程调用 / JSON 数据通道 / 超时 / 状态恢复策略 |
| `bfs-engine.test.mjs` | 引擎单测（node:test + 假适配器，7 用例） |

## 运行

```bash
# 单测（引擎语义，无需 agent-browser）
node --test tools/browser-bfs/bfs-engine.test.mjs

# 四端遍历（需 agent-browser + gtimeout/timeout；macOS 无 gtimeout 时自动回退 Node 超时）
node tools/browser-bfs/public-surface-bfs.mjs
```

## 环境变量（契约保持）

`BFS_MAX_DEPTH`（8）/ `BFS_MAX_STEPS`（500）/ `BFS_WAIT_MS` / `BFS_COMMAND_TIMEOUT_MS`（10s）/
`BFS_ENDPOINT_TIMEOUT_MS`（60s）/ `BFS_REPORT_DIR` / `BFS_ENDPOINTS`（student,teacher,parent,admin）/
`BFS_STATE` / `BFS_SESSION` / `BFS_DEBUG=1`（透出 agent-browser stderr）

## 99-1/99-2 深化点

- **引擎可测**：BFS 语义（去重/深度/步数/错误）经假适配器单测覆盖——白天 7 连修的超时/挂起/隔离问题收敛在适配层
- **父快照修正**：原版在分支末态取父快照致控件错位漏探，现先 restore 再快照
- **stopReason 补齐**：while 条件退出（非 break）时正确标记 max-steps/endpoint-timeout
- **JSON 通道分离**：stdout 优先整段 JSON 解析，stderr 日志仅调试透出（不再从混拼串找 `{`）
- **超时去硬依赖**：gtimeout/timeout 双检测，均不可用回退 Node spawnSync timeout
- **恢复策略单点**：`restore(url, path)` 收敛在适配层——agent-browser 提供会话内导航恢复时仅替换该函数
