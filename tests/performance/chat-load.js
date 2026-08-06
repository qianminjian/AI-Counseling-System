// TEST-004: MindSafe 性能压测基线（k6）
// 目标：100 并发 SSE 流式对话，验证首 token < 1s、P95 < 5s、错误率 < 1%
//
// 用法：
//   k6 run tests/performance/chat-load.js
//   k6 run --env BASE_URL=https://staging.example.com --env INVITE_CODE=xxxxxxxx tests/performance/chat-load.js
//   k6 run --env TOKEN=<jwt> tests/performance/chat-load.js          # 使用已有用户
//
// 认证（P1-DEP 修复：此前用假 token 'test-token-placeholder'，真实接口会 401）：
//   - 提供 TOKEN 则直接使用（压测已注册/登录用户）
//   - 否则用 POST /api/v1/auth/trial/register 注册试用用户（需 INVITE_CODE；
//     age 固定 14（≥14 免监护人同意 SMS 闭环），consentVersion=v0.1）
//
// 真实链路（P1-DEP 修复：此前 POST /api/v1/chat/send 是 404 死接口）：
//   1. POST /api/v1/chat/sessions                       → 创建会话，取 data.sessionId
//   2. POST /api/v1/chat/sessions/{id}/messages         → SSE 流式对话回复
//
// 首 token 指标（P1-DEP 修复：此前 totalTime*0.3 是伪造数据）：
//   k6 对 SSE 响应在收到响应头时即记录 timings.waiting（TTFB），
//   首个 SSE chunk 紧随响应头到达 → TTFB 是首 token 时间的真实近似。
//   sse_total_ms 为完整流时长（后端流完成后关闭连接，k6 读到 EOF 即结束；
//   流挂死时被 timeout 截断，可据此发现服务端流异常）。
//
// 场景设计：
//   1. ramp-up: 30s 内从 0 → 100 VU
//   2. steady:  100 VU 持续 2min
//   3. ramp-down: 30s 内从 100 → 0

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// ===== 自定义指标 =====
const sseFirstTokenTime = new Trend('sse_first_token_ms', true);
const sseTotalTime = new Trend('sse_total_ms', true);
const sseErrors = new Rate('sse_errors');
const apiErrors = new Rate('api_errors');

// ===== 配置 =====
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN || '';
const INVITE_CODE = __ENV.INVITE_CODE || '';
const CONSENT_VERSION = __ENV.CONSENT_VERSION || 'v0.1'; // 与后端 TrialAuthService.CURRENT_CONSENT_VERSION 对齐
const SSE_TIMEOUT = __ENV.SSE_TIMEOUT || '30s';          // SSE 流超时（流挂死的兜底）

export const options = {
  scenarios: {
    chat_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 100 },  // ramp-up
        { duration: '2m', target: 100 },   // steady state
        { duration: '30s', target: 0 },    // ramp-down
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    'sse_first_token_ms': ['p(95)<1000'],   // 首 token P95 < 1s（真实 TTFB）
    'sse_total_ms': ['p(95)<5000'],          // 完整流时长 P95 < 5s
    'sse_errors': ['rate<0.01'],             // SSE 错误率 < 1%
    'api_errors': ['rate<0.01'],
    'http_req_duration': ['p(95)<3000'],     // 普通 API P95 < 3s
  },
};

// ===== 测试数据 =====
const MESSAGES = [
  '今天在学校被同学嘲笑了，心里很难受',
  '我最近总是睡不着觉，脑子里停不下来',
  '妈妈和爸爸又吵架了，我不想回家',
  '考试没考好，觉得自己很笨',
  '没有人愿意和我一起玩',
  '老师今天表扬我了，很开心！',
  '我有点紧张，明天要上台演讲',
  '好朋友转学了，我很想她',
];

// 每个 VU 独立运行时环境：会话 ID 在 VU 内跨迭代复用（避免每迭代重复建会话）
let sessionId = null;

// ===== 测试场景 =====
export default function (data) {
  const headers = {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${data.token}`,
  };

  group('SSE 流式对话', () => {
    testSSEChat(headers);
  });

  group('普通 API', () => {
    testHealthCheck();
  });

  // 模拟用户思考间隔
  sleep(Math.random() * 3 + 1); // 1-4s
}

/**
 * SSE 流式对话压测（真实接口链路）
 */
function testSSEChat(headers) {
  // 1. 懒创建会话（每 VU 一个，跨迭代复用）
  if (!sessionId) {
    const createRes = http.post(
      `${BASE_URL}/api/v1/chat/sessions`,
      JSON.stringify({ emotionTag: 'neutral', channel: 'web' }),
      { headers, tags: { name: 'Create Session' } }
    );

    const createOk = check(createRes, {
      'create session status is 200': (r) => r.status === 200,
      'create session success': (r) => {
        try { return r.json() && r.json().code === 0; } catch (e) { return false; }
      },
    });
    apiErrors.add(!createOk);

    if (createOk) {
      sessionId = createRes.json().data.sessionId;
    } else {
      console.error(`Create session failed: status=${createRes.status}, body=${createRes.body.slice(0, 300)}, vu=${__VU}`);
      sseErrors.add(true);
      return; // 会话创建失败，本轮跳过发消息
    }
  }

  // 2. SSE 发消息（流式回复）
  const msg = MESSAGES[Math.floor(Math.random() * MESSAGES.length)];
  const res = http.post(
    `${BASE_URL}/api/v1/chat/sessions/${sessionId}/messages`,
    JSON.stringify({ content: msg }),
    {
      headers,
      timeout: SSE_TIMEOUT,
      tags: { name: 'SSE Chat' },
    }
  );

  // 首 token = TTFB（响应头到达时刻，SSE 首 chunk 紧随其后，真实近似）
  sseFirstTokenTime.add(res.timings.waiting);
  // 完整流时长：后端流结束后关闭连接，k6 读到 EOF；流挂死则被 timeout 截断
  sseTotalTime.add(res.timings.duration);

  const success = check(res, {
    'status is 200': (r) => r.status === 200,
    'has SSE response body': (r) => r.body && r.body.length > 0,
    'response time < 10s': (r) => r.timings.duration < 10000,
  });

  sseErrors.add(!success);

  if (!success) {
    console.error(`SSE Chat failed: status=${res.status}, vu=${__VU}, iter=${__ITER}, body=${(res.body || '').slice(0, 200)}`);
  }
}

/**
 * 健康检查（验证服务可用性）
 */
function testHealthCheck() {
  const res = http.get(`${BASE_URL}/actuator/health`, {
    tags: { name: 'Health Check' },
  });

  const success = check(res, {
    'health is 200': (r) => r.status === 200,
    'status is UP': (r) => r.body && r.body.includes('UP'),
  });

  apiErrors.add(!success);
}

// ===== 测试生命周期 =====
export function setup() {
  // 1. 验证服务可达
  const health = http.get(`${BASE_URL}/actuator/health`);
  if (health.status !== 200) {
    console.warn(`⚠️ 服务可能不可达: ${BASE_URL} (status=${health.status})`);
  }

  // 2. 获取压测 token：优先用已有 TOKEN，否则试用注册
  if (TOKEN) {
    console.log(`🔑 使用提供的 TOKEN（长度 ${TOKEN.length}）`);
    return { token: TOKEN };
  }

  if (!INVITE_CODE) {
    throw new Error('必须提供 --env TOKEN=<jwt>（已注册用户）或 --env INVITE_CODE=<邀请码>（试用注册）');
  }

  const regBody = JSON.stringify({
    inviteCode: INVITE_CODE,
    pseudonym: `压测${String(Date.now()).slice(-6)}`, // 2-12 字且唯一，避免昵称冲突
    age: 14, // ≥14 免监护人同意 SMS 闭环（生产 MINDSAFE_CONSENT_TRIAL_AUTO_GRANT=false 时 age<14 会被拦截）
    consentVersion: CONSENT_VERSION,
    role: 'other',
    gender: 'male',
  });
  const regRes = http.post(
    `${BASE_URL}/api/v1/auth/trial/register`,
    regBody,
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'Trial Register' } }
  );

  const regOk = check(regRes, {
    'trial register status is 200': (r) => r.status === 200,
    'trial register success': (r) => {
      try { return r.json() && r.json().code === 0; } catch (e) { return false; }
    },
  });
  if (!regOk) {
    throw new Error(`试用注册失败: status=${regRes.status}, body=${(regRes.body || '').slice(0, 500)}`);
  }

  const token = regRes.json().data.token;
  console.log(`🔑 试用注册成功，获取 token（长度 ${token.length}）`);
  return { token };
}

export function teardown(data) {
  console.log(`压测完成: token 前缀=${data.token ? data.token.slice(0, 8) + '...' : 'N/A'}, 结束=${new Date().toISOString()}`);
}
