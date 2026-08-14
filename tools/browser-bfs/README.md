# Browser Agent BFS 编排器（只读公共表面）

`public-surface-bfs.mjs` 是 UI-TEST-018 的最小执行器，当前只遍历四端未登录公共页面，并主动跳过登录、注册、提交、确认、导出、绑定、处置和其他持久化控件。

它输出每端的状态键、控件键、BFS 深度、操作步数、截图、异常和停止原因。默认上限为深度 8、500 步、单命令 10 秒、单端 60 秒；试运行可用 `BFS_MAX_DEPTH`、`BFS_MAX_STEPS`、`BFS_WAIT_MS`、`BFS_COMMAND_TIMEOUT_MS` 和 `BFS_ENDPOINT_TIMEOUT_MS` 覆盖。

可用 `BFS_ENDPOINTS=student`（逗号分隔）单独验证某一端；未设置时依次运行四端。每次运行只使用一个命名 `--session`，完成后关闭该 session 并执行全局清理。

执行前提：`agent-browser` 必须为本次命名 session 提供隔离上下文；执行器不传用户持久 profile。若 CLI 输出 `--profile ignored`，程序会立即失败；这类结果不得计入测试证据。该执行器尚未覆盖登录态业务、弹窗通用回退、真实测试夹具或四端联动，因此不能替代 UI-TEST-018 的最终完成闸门。

示例：

```bash
BFS_MAX_DEPTH=1 BFS_MAX_STEPS=8 node tools/browser-bfs/public-surface-bfs.mjs
```
