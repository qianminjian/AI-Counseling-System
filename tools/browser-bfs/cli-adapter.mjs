// cli-adapter.mjs —— agent-browser CLI 适配层（99-2，2026-08-14）
//
// 单一 seam：BFS 引擎只依赖本模块的接口面（open/snapshot/click/screenshot/
// restore/close），不感知 agent-browser 进程细节。本模块负责：
//   - 进程调用（--session 会话隔离）
//   - JSON 数据通道（stdout 独立解析，stderr 日志分离——不再从混拼串"找第一个 {"）
//   - 超时控制（优先 gtimeout/timeout 杀进程树；不可用时回退 Node spawnSync timeout）
//   - 状态恢复策略（restore：优先同会话前缀回退，失败回退全量重放）
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";

const COMMAND_TIMEOUT_MS = Number(process.env.BFS_COMMAND_TIMEOUT_MS ?? 10000);
const WAIT_MS = Number(process.env.BFS_WAIT_MS ?? 250);

// 超时包装器（2026-08-29 BUG-S-001 修复）：gtimeout(macOS coreutils) / timeout(Linux) 优先时必须
// 外层包装 agent-browser 命令（argv 前插），原实现误将 timeout 二进制插入 agent-browser 参数数组
// 中间，被 daemon 当作子命令拒绝（Unknown command: gtimeout）。均不可用时回退 spawnSync 原生 timeout。
// L4：探测结果缓存为模块级常量（避免每次 CLI 调用重复 spawn 探测进程）
const TIMEOUT_BIN = (() => {
  for (const bin of ["gtimeout", "timeout"]) {
    const probe = spawnSync(bin, ["--version"], { encoding: "utf8" });
    if (!probe.error && probe.status === 0) return bin;
  }
  return null;
})();

function cli(session, args) {
  // --session 为隔离会话；显式 profile 仅在首个命令传入，daemon 会拒绝后续 profile
  const agentArgs = ["agent-browser", "--session", session, ...args];
  const argv = TIMEOUT_BIN
    ? [TIMEOUT_BIN, "-k", "2", String(Math.max(1, Math.ceil(COMMAND_TIMEOUT_MS / 1000))), ...agentArgs]
    : agentArgs;
  const child = spawnSync(argv[0], argv.slice(1), {
    encoding: "utf8",
    timeout: COMMAND_TIMEOUT_MS + 2000,
    maxBuffer: 4 * 1024 * 1024,
  });
  if (child.error) throw child.error;
  if (child.status !== 0) {
    throw new Error(`agent-browser exited ${child.status}: ${(child.stderr ?? "").slice(-1000)}`);
  }
  // 数据通道：stdout 为 JSON（agent-browser --json 语义）；日志噪音走 stderr，仅调试时透出
  if (process.env.BFS_DEBUG === "1" && child.stderr) {
    console.error(`[cli:${session}] ${child.stderr.slice(0, 500)}`);
  }
  return child.stdout ?? "";
}

function parseJson(output) {
  // 99-2：优先整段 stdout 解析；失败再找第一个 {（兼容旧版输出混入日志）
  const trimmed = output.trim();
  try {
    return JSON.parse(trimmed);
  } catch {
    const start = trimmed.indexOf("{");
    if (start < 0) throw new Error(`agent-browser JSON 输出缺失: ${output.slice(-500)}`);
    return JSON.parse(trimmed.slice(start));
  }
}

/** 设备/页面控件过滤（交互角色 + 排除登录提交按钮，避免误操作） */
export const INTERACTIVE_ROLES = new Set([
  "button", "checkbox", "combobox", "link", "menuitem", "radio", "searchbox", "spinbutton", "tab", "textbox",
]);

export function controlsOf(snapshot) {
  return Object.entries(snapshot?.data?.refs ?? {})
    .filter(([, value]) => INTERACTIVE_ROLES.has(value.role))
    .map(([ref, value]) => ({ ref, role: value.role, name: value.name ?? "" }))
    .filter((control) => control.role !== "button" || control.name !== "进入 🚀");
}

/** 状态哈希（全量快照文本归一化，防循环） */
export function stateKeyOf(snapshot) {
  const origin = snapshot?.data?.origin ?? "";
  const text = snapshot?.data?.snapshot ?? "";
  return createHash("sha256").update(`${origin}\n${text.replace(/\s+/g, " ").trim()}`).digest("hex").slice(0, 16);
}

/**
 * agent-browser 适配器（BFS 引擎依赖的接口面实现）。
 * restore(url, path)：状态恢复策略收敛至适配层（99-1）——CLI 进程每次重建、
 * SPA 页面状态无法跨进程持久化，恢复 = open + 重放路径；当 agent-browser 提供
 * 会话内导航恢复能力时，仅需替换本函数实现（引擎零改动）。
 */
export function createCliAdapter({ session, statePath }) {
  function loadState() {
    // H1：BFS_STATE 认证态经 agent-browser 真实 state 命令加载（URL fragment 无效）
    if (statePath) cli(session, ["state", "load", statePath]);
  }

  function snapshot() {
    return parseJson(cli(session, ["snapshot", "-i", "--json"]));
  }

  function wait(ms = WAIT_MS) {
    cli(session, ["wait", String(ms)]);
  }

  function click(control) {
    try {
      cli(session, ["find", "role", control.role, "click", "--name", control.name]);
      return true;
    } catch (error) {
      // Ant/Taro 可访问名含 emoji/空格时语义查找失败 → 页面局部 DOM 兜底
      const name = JSON.stringify(control.name.trim());
      const role = JSON.stringify(control.role);
      const script = `(()=>{const role=${role}, name=${name}; const norm=v=>String(v||'').replace(/\\s+/g,''); const target=norm(name); const nodes=[...document.querySelectorAll('[role="'+role+'"],button,a')]; const node=nodes.find(x=>[x.textContent,x.getAttribute('aria-label'),x.getAttribute('title')].some(v=>norm(v).includes(target))); if(!node) return 'not-found'; node.click(); return 'clicked'})()`;
      const output = cli(session, ["eval", script]);
      if (!output.includes('"clicked"')) throw error;
      return true;
    }
  }

  function open(url) {
    cli(session, ["open", url]);
    wait();
  }

  /** 恢复到指定操作路径的状态（恢复策略单点，引擎零感知） */
  function restore(url, path) {
    open(url);
    for (let i = 0; i < path.length; i += 1) {
      if (!click(path[i])) throw new Error(`non-replayable control: ${path[i].role}:${path[i].name}`);
      wait();
    }
  }

  function screenshot(path) {
    cli(session, ["screenshot", path]);
  }

  function close() {
    try { cli(session, ["close"]); } catch { /* 会话已关闭 */ }
  }

  return { snapshot, wait, click, open, restore, screenshot, close, session, loadState };
}
