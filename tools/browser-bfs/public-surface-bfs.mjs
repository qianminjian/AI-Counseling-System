#!/usr/bin/env node

import { execFileSync, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const MAX_DEPTH = Number(process.env.BFS_MAX_DEPTH ?? 8);
const MAX_STEPS = Number(process.env.BFS_MAX_STEPS ?? 500);
const WAIT_MS = Number(process.env.BFS_WAIT_MS ?? 250);
const COMMAND_TIMEOUT_MS = Number(process.env.BFS_COMMAND_TIMEOUT_MS ?? 10000);
const ENDPOINT_TIMEOUT_MS = Number(process.env.BFS_ENDPOINT_TIMEOUT_MS ?? 60000);
const REPORT_DIR = process.env.BFS_REPORT_DIR ?? "reports/browser-test/UI-TEST-018/public-bfs";
const SELECTED_ENDPOINTS = new Set((process.env.BFS_ENDPOINTS ?? "student,teacher,parent,admin").split(",").map(value => value.trim()).filter(Boolean));

const endpoints = [
  ["student", "https://yun.gxjugu.com/mindsafe/"],
  ["teacher", "https://yun.gxjugu.com/teacher/"],
  ["parent", "https://yun.gxjugu.com/parent/"],
  ["admin", "https://yun.gxjugu.com/admin/"]
];

const interactiveRoles = new Set([
  "button", "checkbox", "combobox", "link", "menuitem", "radio", "searchbox", "spinbutton", "tab", "textbox"
]);

function cli(session, profile, args, timeout = COMMAND_TIMEOUT_MS) {
  // --session is an isolated agent-browser session. Do not pass a persistent
  // profile on every command: the daemon rejects/ignores that flag after the
  // first command, which can otherwise create a false isolation failure.
  const child = spawnSync("agent-browser", ["--session", session, ...args], {
    encoding: "utf8",
    timeout,
    maxBuffer: 4 * 1024 * 1024
  });
  if (child.error) throw child.error;
  const output = `${child.stdout ?? ""}\n${child.stderr ?? ""}`;
  if (child.status !== 0) throw new Error(`agent-browser exited ${child.status}: ${output.slice(-1000)}`);
  if (output.includes("--profile ignored")) {
    throw new Error("browser isolation failed: agent-browser ignored an explicitly supplied profile");
  }
  return output;
}

function parseJson(output) {
  const start = output.indexOf("{");
  if (start < 0) throw new Error(`agent-browser JSON output missing: ${output.slice(-500)}`);
  return JSON.parse(output.slice(start));
}

function snapshot(session, profile) {
  return parseJson(cli(session, profile, ["snapshot", "-i", "--json"]));
}

function stateKey(snap) {
  const origin = snap?.data?.origin ?? "";
  const text = snap?.data?.snapshot ?? "";
  return createHash("sha256").update(`${origin}\n${text.replace(/\s+/g, " ").trim()}`).digest("hex").slice(0, 16);
}

function controls(snap) {
  return Object.entries(snap?.data?.refs ?? {})
    .filter(([, value]) => interactiveRoles.has(value.role))
    .map(([ref, value]) => ({ ref, role: value.role, name: value.name ?? "" }))
    .filter(control => control.role !== "button" || control.name !== "进入 🚀");
}

function safeName(value) {
  return value.replace(/[^\p{L}\p{N}_-]+/gu, "_").slice(0, 60) || "unnamed";
}

function screenshot(session, profile, endpoint, index, label) {
  const path = join(REPORT_DIR, `${endpoint}-${String(index).padStart(4, "0")}-${safeName(label)}.png`);
  cli(session, profile, ["screenshot", path]);
  return path;
}

function locatorAction(session, profile, control) {
  // Public-surface traversal intentionally clicks only non-submit controls.
  // Text fields, login/register submit buttons, and destructive controls are recorded, not acted on.
  const normalizedName = control.name.replace(/\s+/g, "");
  if (control.role === "button" && /登录|注册|提交|确认|绑定|导出|处理|误报|激活|修改|删除|撤回|恢复|升级/.test(normalizedName)) return false;
  if (!["button", "link", "menuitem", "tab"].includes(control.role)) return false;
  cli(session, profile, ["find", "role", control.role, "click", "--name", control.name]);
  return true;
}

function resetAndReplay(session, profile, url, path) {
  cli(session, profile, ["open", url]);
  cli(session, profile, ["wait", String(WAIT_MS)]);
  // The first open can leave a new session on about:blank; a second open is harmless and stabilizes it.
  cli(session, profile, ["open", url]);
  cli(session, profile, ["wait", String(WAIT_MS)]);
  for (const control of path) {
    if (!locatorAction(session, profile, control)) throw new Error(`non-replayable control: ${control.role}:${control.name}`);
    cli(session, profile, ["wait", String(WAIT_MS)]);
  }
}

function runEndpoint(endpoint, url) {
  const profile = mkdtempSync(join(tmpdir(), `codex-public-bfs-${endpoint}-`));
  const session = `public-bfs-${endpoint}-${Date.now()}`;
  const result = {
    endpoint,
    url,
    algorithm: "breadth-first",
    maxDepth: MAX_DEPTH,
    maxSteps: MAX_STEPS,
    visitedStates: [],
    operatedControls: [],
    screenshots: [],
    errors: [],
    stopReason: "completed"
  };
  const queue = [{ depth: 0, path: [] }];
  const visited = new Set();
  let steps = 0;
  const deadline = Date.now() + ENDPOINT_TIMEOUT_MS;

  try {
    resetAndReplay(session, profile, url, []);
    const initial = snapshot(session, profile);
    const initialKey = stateKey(initial);
    visited.add(initialKey);
    result.visitedStates.push({ key: initialKey, depth: 0, controls: controls(initial) });
    result.screenshots.push(screenshot(session, profile, endpoint, 0, "enter"));

    while (queue.length > 0 && steps < MAX_STEPS && Date.now() < deadline) {
      if (Date.now() >= deadline) { result.stopReason = "endpoint-timeout"; break; }
      const current = queue.shift();
      if (current.depth >= MAX_DEPTH) continue;
      const parentSnap = snapshot(session, profile);
      for (const control of controls(parentSnap)) {
        if (Date.now() >= deadline) { result.stopReason = "endpoint-timeout"; break; }
        if (steps >= MAX_STEPS) { result.stopReason = "max-steps"; break; }
        if (current.path.some(item => item.role === control.role && item.name === control.name)) continue;
        steps += 1;
        try {
          resetAndReplay(session, profile, url, current.path);
          if (!locatorAction(session, profile, control)) continue;
          cli(session, profile, ["wait", String(WAIT_MS)]);
          const after = snapshot(session, profile);
          const key = stateKey(after);
          result.operatedControls.push({ step: steps, depth: current.depth + 1, control, parentPath: current.path });
          result.screenshots.push(screenshot(session, profile, endpoint, steps, `${control.role}-${control.name}`));
          if (!visited.has(key)) {
            visited.add(key);
            result.visitedStates.push({ key, depth: current.depth + 1, controls: controls(after) });
            queue.push({ depth: current.depth + 1, path: [...current.path, control] });
          }
        } catch (error) {
          result.errors.push({ step: steps, control, message: String(error) });
        }
      }
    }
  } catch (error) {
    result.stopReason = "error";
    result.errors.push({ step: steps, message: String(error) });
  } finally {
    try { cli(session, profile, ["close"]); } catch {}
    try { execFileSync("agent-browser", ["close", "--all"], { encoding: "utf8", timeout: 10000 }); } catch {}
  }
  result.steps = steps;
  result.queueMaxDepth = result.visitedStates.reduce((max, state) => Math.max(max, state.depth), 0);
  return result;
}

mkdirSync(REPORT_DIR, { recursive: true });
const report = { generated: new Date().toISOString(), mode: "public-readonly", endpoints: [] };
for (const [endpoint, url] of endpoints) {
  if (SELECTED_ENDPOINTS.has(endpoint)) report.endpoints.push(runEndpoint(endpoint, url));
}
writeFileSync(join(REPORT_DIR, "manifest.json"), `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify(report, null, 2));
