# Browser Agent BFS 编排器（只读公共表面）

`public-surface-bfs.mjs` 是 UI-TEST-018 的最小执行器，当前只遍历四端未登录公共页面，并主动跳过登录、注册、提交、确认、导出、绑定、处置和其他持久化控件。

它输出每端的状态键、控件键、BFS 深度、操作步数、截图、异常和停止原因。默认上限为深度 8、500 步；试运行可用 `BFS_MAX_DEPTH`、`BFS_MAX_STEPS` 和 `BFS_WAIT_MS` 覆盖。

执行前提：`agent-browser` 必须能为本次会话使用临时 profile。若 CLI 输出 `--profile ignored` 或 `daemon already running`，程序会立即失败；这类结果不得计入测试证据。该执行器尚未覆盖登录态业务、弹窗通用回退、真实测试夹具或四端联动，因此不能替代 UI-TEST-018 的最终完成闸门。

示例：

```bash
BFS_MAX_DEPTH=1 BFS_MAX_STEPS=8 node tools/browser-bfs/public-surface-bfs.mjs
```
