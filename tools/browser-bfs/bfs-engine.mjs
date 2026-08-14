// bfs-engine.mjs —— BFS 遍历引擎（99-1，2026-08-14）
//
// 纯逻辑引擎：不感知 agent-browser 进程细节，只依赖注入适配器的接口面
// （open/snapshot/wait/click/restore/screenshot/close）。可注入假适配器单测
// BFS 语义（visited 去重 / 深度限制 / 步数限制 / 错误收集）——白天 7 连修的
// 超时/挂起/隔离问题全部收敛在 CLI 适配层，引擎侧不再真机试错。
import { controlsOf, stateKeyOf } from "./cli-adapter.mjs";

/** 安全文件名（控件名 → 路径片段） */
function safeName(value) {
  return value.replace(/[^\p{L}\p{N}_-]+/gu, "_").slice(0, 60) || "unnamed";
}

/**
 * 广度优先遍历（公开只读面：非提交类控件可点击）。
 * @param {object} adapter  注入的适配器（CLI 或假适配器）
 * @param {object} options  { url, endpoint, maxDepth, maxSteps, deadlineMs, screenshotDir }
 * @returns {object} 遍历报告（visitedStates/operatedControls/screenshots/errors/stopReason）
 */
export function runBfs(adapter, { url, endpoint, maxDepth, maxSteps, deadlineMs, screenshotDir }) {
  const result = {
    endpoint,
    url,
    algorithm: "breadth-first",
    maxDepth,
    maxSteps,
    visitedStates: [],
    operatedControls: [],
    screenshots: [],
    errors: [],
    stopReason: "completed",
  };
  const queue = [{ depth: 0, path: [] }];
  const visited = new Set();
  const deadline = Date.now() + deadlineMs;
  let steps = 0;

  adapter.open(url);
  const initial = adapter.snapshot();
  const initialKey = stateKeyOf(initial);
  visited.add(initialKey);
  result.visitedStates.push({ key: initialKey, depth: 0, controls: controlsOf(initial) });

  while (queue.length > 0 && steps < maxSteps && Date.now() < deadline) {
    if (Date.now() >= deadline) { result.stopReason = "endpoint-timeout"; break; }
    const current = queue.shift();
    if (current.depth >= maxDepth) continue;

    // H2：父状态恢复失败（CLI 不可用/路径不可重放）→ 记录错误跳过该节点，不拖垮整端
    try {
      // 99-1 修正：先恢复到父状态再取快照（原版在分支末态取父快照致控件错位漏探）
      adapter.restore(url, current.path);
    } catch (error) {
      result.errors.push({ step: steps, control: null, message: `restore failed: ${String(error)}` });
      continue;
    }
    const parentSnap = adapter.snapshot();
    for (const [index, control] of controlsOf(parentSnap).entries()) {
      if (Date.now() >= deadline) { result.stopReason = "endpoint-timeout"; break; }
      if (steps >= maxSteps) { result.stopReason = "max-steps"; break; }
      // 路径内防重复（同一路径不重复操作同名同角色控件）
      if (current.path.some((item) => item.role === control.role && item.name === control.name)) continue;
      steps += 1;
      try {
        // L1：首个控件前状态即父状态（循环外已 restore），跳过冗余恢复
        if (index > 0) adapter.restore(url, current.path);
        if (!adapter.click(control)) continue;
        adapter.wait();
        const after = adapter.snapshot();
        const key = stateKeyOf(after);
        result.operatedControls.push({ step: steps, depth: current.depth + 1, control, parentPath: current.path });
        if (screenshotDir) {
          const shot = `${screenshotDir}/${endpoint}-${String(steps).padStart(4, "0")}-${safeName(`${control.role}-${control.name}`)}.png`;
          adapter.screenshot(shot);
          result.screenshots.push(shot);
        }
        if (!visited.has(key)) {
          visited.add(key);
          result.visitedStates.push({ key, depth: current.depth + 1, controls: controlsOf(after) });
          queue.push({ depth: current.depth + 1, path: [...current.path, control] });
        }
      } catch (error) {
        result.errors.push({ step: steps, control, message: String(error) });
      }
    }
  }
  result.steps = steps;
  // while 条件退出（非 break）时补齐终止原因（99-1）
  if (result.stopReason === "completed") {
    if (steps >= maxSteps) result.stopReason = "max-steps";
    else if (Date.now() >= deadline) result.stopReason = "endpoint-timeout";
  }
  result.queueMaxDepth = result.visitedStates.reduce((max, state) => Math.max(max, state.depth), 0);
  return result;
}
