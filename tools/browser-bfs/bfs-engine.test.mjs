// bfs-engine.test.mjs —— BFS 引擎单元测试（99-1，2026-08-14）
//
// 注入假适配器（内存状态图），验证 BFS 语义：
// visited 去重 / 深度限制 / 步数限制 / 错误收集 / 路径防重复 / 截图。
// 运行：node --test tools/browser-bfs/bfs-engine.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { runBfs } from "./bfs-engine.mjs";

/**
 * 内存状态图假适配器：
 *   P0  →(A)→ S1 →(C)→ S3
 *   P0  →(B)→ S2
 * snapshot 文本 = 当前状态名（stateKeyOf 归一化后唯一）
 */
function fakeAdapter() {
  const state = { current: "P0" };
  const graph = {
    P0: { refs: { r1: { role: "link", name: "A" }, r2: { role: "button", name: "B" } } },
    S1: { refs: { r3: { role: "link", name: "C" } } },
    S2: { refs: {} },
    S3: { refs: {} },
  };
  const calls = { open: 0, restore: 0, click: [], screenshot: [] };

  return {
    calls,
    graph,
    snapshot() {
      return { data: { origin: "https://t", snapshot: state.current, refs: graph[state.current].refs } };
    },
    open() { calls.open += 1; state.current = "P0"; },
    wait() {},
    click(control) {
      calls.click.push(control.name);
      if (state.current === "P0" && control.name === "A") state.current = "S1";
      else if (state.current === "P0" && control.name === "B") state.current = "S2";
      else if (state.current === "S1" && control.name === "C") state.current = "S3";
      else return false;
      return true;
    },
    restore(_url, path) {
      calls.restore += 1;
      state.current = "P0";
      for (const control of path) {
        if (!this.click(control)) throw new Error(`non-replayable: ${control.name}`);
      }
    },
    screenshot(p) { calls.screenshot.push(p); },
    close() {},
  };
}

function baseOpts(overrides = {}) {
  return {
    url: "https://t/",
    endpoint: "test",
    maxDepth: 8,
    maxSteps: 500,
    deadlineMs: 60_000,
    screenshotDir: "/tmp/shots",
    ...overrides,
  };
}

test("99-1：BFS 遍历可达状态（A→C 与 B 两分支）", () => {
  const adapter = fakeAdapter();
  const result = runBfs(adapter, baseOpts());

  const visitedNames = result.visitedStates.map((s) => adapter.graph[s.key] ? s.key : s.key);
  assert.ok(result.visitedStates.length >= 3, `visited=${result.visitedStates.length}`);
  assert.equal(result.errors.length, 0);
  // A→S1→C→S3 路径被记录
  assert.ok(result.operatedControls.some((o) => o.control.name === "A"));
  assert.ok(result.operatedControls.some((o) => o.control.name === "C"));
  assert.ok(result.operatedControls.some((o) => o.control.name === "B"));
});

test("99-1：visited 去重——同状态不再重复入队（防循环）", () => {
  const adapter = fakeAdapter();
  // 扩展图：S1 里再出现 A（回到 P0 相似状态）→ 已访问跳过
  adapter.graph.S1.refs.r4 = { role: "button", name: "A" };
  adapter.graph.S1.refs = { ...adapter.graph.S1.refs }; // 触发快照变更不必要，仅保持结构
  const result = runBfs(adapter, baseOpts());
  // S1 点击 A 后回到 P0 文本状态 → key 已访问，不入队
  const p0Visits = result.visitedStates.filter((s) => s.key === result.visitedStates[0].key);
  assert.equal(p0Visits.length, 1, "P0 状态仅记录一次");
});

test("99-1：深度限制——maxDepth=1 不探索 S1 的子状态", () => {
  const adapter = fakeAdapter();
  const result = runBfs(adapter, baseOpts({ maxDepth: 1 }));
  // 只操作了 P0 上的 A/B（深度 1）；C 在深度 2 不被操作
  assert.ok(result.operatedControls.some((o) => o.control.name === "A"));
  assert.ok(!result.operatedControls.some((o) => o.control.name === "C"), "深度 2 控件不应被操作");
});

test("99-1：步数限制——maxSteps=2 时停止", () => {
  const adapter = fakeAdapter();
  const result = runBfs(adapter, baseOpts({ maxSteps: 2 }));
  assert.ok(result.steps <= 2);
  assert.equal(result.stopReason, "max-steps");
});

test("99-1：错误收集——click 抛错记录 errors 并继续", () => {
  const adapter = fakeAdapter();
  // 使控件 C 的点击抛错（restore 重放与首次操作都会失败，错误被引擎捕获）
  const originalClick = adapter.click.bind(adapter);
  adapter.click = (control) => {
    if (control.name === "C") throw new Error("boom: C 不可点击");
    return originalClick(control);
  };
  const result = runBfs(adapter, baseOpts());
  assert.ok(result.errors.length >= 1, `errors=${result.errors.length}`);
  assert.ok(result.errors.some((e) => e.message.includes("boom")));
  // A/B 分支不受影响，仍被操作
  assert.ok(result.operatedControls.some((o) => o.control.name === "A"));
  assert.ok(result.operatedControls.some((o) => o.control.name === "B"));
});

test("99-1：截图——screenshotDir 提供时逐步截图", () => {
  const adapter = fakeAdapter();
  const result = runBfs(adapter, baseOpts());
  assert.ok(result.screenshots.length > 0);
  assert.ok(adapter.calls.screenshot.length === result.screenshots.length);
  assert.ok(result.screenshots[0].startsWith("/tmp/shots/test-"));
});

test("H2：父状态 restore 失败记录错误并跳过该节点（不拖垮整端）", () => {
  const adapter = fakeAdapter();
  // 路径 [A]（进入 S1）的 restore 抛错 → 该节点跳过，B 分支不受影响
  const originalRestore = adapter.restore.bind(adapter);
  adapter.restore = (_url, path) => {
    if (path.length === 1 && path[0].name === "A") throw new Error("restore boom: A 不可重放");
    return originalRestore(_url, path);
  };
  const result = runBfs(adapter, baseOpts());
  assert.ok(result.errors.some((e) => e.message.includes("restore boom")));
  // B 分支仍被操作，A 分支的错误不阻断遍历
  assert.ok(result.operatedControls.some((o) => o.control.name === "B"));
});

test("99-1：路径内防重复——同名同角色控件在路径内不重复操作", () => {
  const adapter = fakeAdapter();
  // P0 出现两个 A（同名 link）→ 同一路径只操作一次
  adapter.graph.P0.refs.r5 = { role: "link", name: "A" };
  const result = runBfs(adapter, baseOpts({ maxDepth: 1, maxSteps: 3 }));
  const aOps = result.operatedControls.filter((o) => o.control.name === "A");
  assert.ok(aOps.length <= 2, `A 操作次数=${aOps.length}（深度 1 下最多两条路径各一次）`);
});
