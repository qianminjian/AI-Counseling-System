#!/usr/bin/env node
// public-surface-bfs.mjs —— 四端公开面 BFS 遍历入口（99-1/99-2，2026-08-14 重构）
//
// 职责收敛：环境解析 + 端侧编排 + 报告汇总（引擎逻辑在 bfs-engine.mjs，
// agent-browser 进程细节在 cli-adapter.mjs）。CLI 环境变量契约保持不变。
//
// 环境变量（兼容原版）：
//   BFS_MAX_DEPTH / BFS_MAX_STEPS / BFS_WAIT_MS / BFS_COMMAND_TIMEOUT_MS /
//   BFS_ENDPOINT_TIMEOUT_MS / BFS_REPORT_DIR / BFS_ENDPOINTS / BFS_STATE / BFS_SESSION

import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { createCliAdapter } from "./cli-adapter.mjs";
import { runBfs } from "./bfs-engine.mjs";

const MAX_DEPTH = Number(process.env.BFS_MAX_DEPTH ?? 8);
const MAX_STEPS = Number(process.env.BFS_MAX_STEPS ?? 500);
const ENDPOINT_TIMEOUT_MS = Number(process.env.BFS_ENDPOINT_TIMEOUT_MS ?? 60000);
const REPORT_DIR = process.env.BFS_REPORT_DIR ?? "reports/browser-test/UI-TEST-018/public-bfs";
const SELECTED_ENDPOINTS = new Set((process.env.BFS_ENDPOINTS ?? "student,teacher,parent,admin")
  .split(",").map((value) => value.trim()).filter(Boolean));
const STATE_PATH = process.env.BFS_STATE ?? "";
const EXISTING_SESSION = process.env.BFS_SESSION ?? "";

const endpoints = [
  ["student", "https://yun.gxjugu.com/mindsafe/"],
  ["teacher", "https://yun.gxjugu.com/teacher/"],
  ["parent", "https://yun.gxjugu.com/parent/"],
  ["admin", "https://yun.gxjugu.com/admin/"],
];

function runEndpoint(endpoint, url) {
  const session = EXISTING_SESSION || `public-bfs-${endpoint}-${Date.now()}`;
  const adapter = createCliAdapter({ session, statePath: STATE_PATH });

  try {
    adapter.loadState(); // H1：认证态经真实 CLI state 命令加载（仅启动一次）
    return runBfs(adapter, {
      url,
      endpoint,
      maxDepth: MAX_DEPTH,
      maxSteps: MAX_STEPS,
      deadlineMs: ENDPOINT_TIMEOUT_MS,
      screenshotDir: REPORT_DIR,
    });
  } catch (error) {
    // H2：单端失败兜底——返回错误报告，主循环继续，manifest 仍产出
    return {
      endpoint,
      url,
      algorithm: "breadth-first",
      maxDepth: MAX_DEPTH,
      maxSteps: MAX_STEPS,
      visitedStates: [],
      operatedControls: [],
      screenshots: [],
      errors: [{ step: 0, message: String(error) }],
      stopReason: "error",
    };
  } finally {
    adapter.close();
  }
}

mkdirSync(REPORT_DIR, { recursive: true });
const report = { generated: new Date().toISOString(), mode: "public-readonly", endpoints: [] };
for (const [endpoint, url] of endpoints) {
  if (SELECTED_ENDPOINTS.has(endpoint)) report.endpoints.push(runEndpoint(endpoint, url));
}
writeFileSync(join(REPORT_DIR, "manifest.json"), `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify(report, null, 2));
