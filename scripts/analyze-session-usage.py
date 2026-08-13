#!/usr/bin/env python3
# analyze-session-usage.py - 会话实际消耗分析（token-usage-report.sh 的子分析器）
#
# 用途：从 Qoder 本地数据还原"每次对话实际花了多少上下文"，回答两个问题：
#   1. 每次会话每轮平均背了多少上下文（累计发送量 / 轮数）
#   2. 消耗集中在哪些行为上（工具调用分布、重复读文件、单次大注入）
#
# 数据源（均为本地 jsonl，无真实计费字段，字节数为真实值、token 为估算）：
#   A. 对话层 ~/.qoder-cn/cache/projects/AI-Counseling-System-*/conversation-history/*/*.jsonl
#      （user/assistant 文本消息，user 消息含系统注入，可精确计算每轮上下文累积）
#   B. 执行层 ~/.qoder/projects/-Users-*-AI-Counseling-System*/transcript/*.jsonl
#      （旧 IDE 执行轨迹：tool_use / tool_result，可统计工具调用与注入体积）
#
# 输出协议（stdout，供 bash 拆分）：
#   <<<SECTION   …… 报告正文 markdown
#   <<<ADVICE    …… 建议行（每行一条，供 bash 追加进 ADVICE 数组）
#   <<<SUMMARY   …… 终端摘要一行
#
# 依赖：python3 标准库，无第三方库。兼容 macOS python3.9（无 match/f-string= 等新语法）

import json
import os
import glob
import sys
import datetime
from collections import Counter, defaultdict

HOME = os.path.expanduser('~')

# ─── 数据源定位 ─────────────────────────────────────────────────────────

def find_conversation_dirs():
    """当前 IDE 对话层：主项目 + dev 副本 的 conversation-history 目录"""
    out = []
    for p in sorted(glob.glob(os.path.join(HOME, '.qoder-cn/cache/projects/AI-Counseling-System*'))):
        ch = os.path.join(p, 'conversation-history')
        if os.path.isdir(ch):
            label = 'dev副本' if '-dev-' in p else '主项目'
            out.append((label, ch))
    return out

def find_transcript_dirs():
    """旧 IDE 执行层：主项目与 dev 副本的 transcript 目录"""
    out = []
    for p in sorted(glob.glob(os.path.join(HOME, '.qoder/projects/-Users-*-AI-Counseling-System*'))):
        t = os.path.join(p, 'transcript')
        if os.path.isdir(t):
            label = 'dev副本' if p.endswith('-dev') else '主项目'
            out.append((label, t))
    return out


# ─── token 估算（与 token-usage-report.sh 口径一致）──────────────────────

def est_tokens(s):
    """bytes -> 估算 tokens：中文占比高按 /2，否则按 /4"""
    if not s:
        return 0
    raw = s.encode('utf-8', errors='ignore')
    cjk = sum(1 for c in s if '\u4e00' <= c <= '\u9fff')
    ratio = cjk / len(s) if len(s) > 0 else 0
    return len(raw) // (2 if ratio > 0.3 else 4)


# ─── 对话层扫描（conversation-history）───────────────────────────────────

def scan_conversations():
    """
    每个会话：轮数、用户输入字节（含系统注入）、助手输出字节、
    累计发送字节（每轮发送=截至该轮全部历史，逐轮累加）、输出 token 估算
    返回：(label, task, turns, in_bytes, out_bytes, out_tok, acc)
    """
    sessions = []
    for label, ch in find_conversation_dirs():
        for f in sorted(glob.glob(os.path.join(ch, '*/*.jsonl'))):
            task = os.path.basename(os.path.dirname(f))
            turns = 0
            in_bytes = out_bytes = acc = out_tok = 0
            hist = 0
            for o in _iter_jsonl_lines(f):
                msg = o.get('message', {})
                if not isinstance(msg, dict):
                    continue
                # 两种数据源结构不同：conversation-history 的 role 在顶层，transcript 在 message 里
                role = o.get('role') or msg.get('role', '')
                content = msg.get('content', [])
                if not isinstance(content, list):
                    continue
                for c in content:
                    if not isinstance(c, dict) or c.get('type') != 'text':
                        continue
                    s = c.get('text', '') or ''
                    b = len(s.encode('utf-8', errors='ignore'))
                    hist += b
                    if role == 'user':
                        turns += 1
                        in_bytes += b
                        acc += hist  # 每轮发送时，上下文=截至本条的全部历史
                    elif role == 'assistant':
                        out_bytes += b
                        out_tok += est_tokens(s)
            sessions.append((label, task, turns, in_bytes, out_bytes, out_tok, acc))
    return sessions

def _iter_jsonl_lines(f):
    with open(f, 'r', errors='ignore') as fh:
        for line in fh:
            try:
                o = json.loads(line)
            except Exception:
                continue
            if isinstance(o, dict):
                yield o


# ─── 执行层扫描（transcript，旧 IDE 轨迹）────────────────────────────────

def scan_transcripts():
    """
    每个会话：工具调用数、注入字节（tool_result）、输出 token、
    最大单次注入、工具名分布、重复读文件计数
    """
    sessions = []  # (label, name, date, tools_n, inject_bytes, out_tok, max_inject, tools, read_paths)
    for label, tdir in find_transcript_dirs():
        for f in sorted(glob.glob(os.path.join(tdir, '*.jsonl'))):
            name = os.path.basename(f)
            tools_n = 0
            inject_bytes = out_tok = max_inject = 0
            tools = Counter()
            read_paths = Counter()
            for o in _iter_jsonl_lines(f):
                msg = o.get('message', {})
                if not isinstance(msg, dict):
                    continue
                role = msg.get('role', '')
                content = msg.get('content', [])
                if not isinstance(content, list):
                    continue
                for c in content:
                    if not isinstance(c, dict):
                        continue
                    t = c.get('type', '')
                    if t == 'tool_use':
                        tools_n += 1
                        tools[c.get('name', '?')] += 1
                        inp = c.get('input', {})
                        nm = c.get('name', '')
                        if isinstance(inp, dict) and 'read' in nm.lower():
                            p = inp.get('file_path') or ''
                            if p:
                                read_paths[p] += 1
                    elif t == 'tool_result':
                        s = c.get('content', '')
                        if isinstance(s, list):
                            s = ' '.join(str(x) for x in s)
                        b = len(str(s).encode('utf-8', errors='ignore'))
                        inject_bytes += b
                        max_inject = max(max_inject, b)
                    elif t == 'text' and role == 'assistant':
                        out_tok += est_tokens(c.get('text', '') or '')
            mtime = os.path.getmtime(f)
            date = datetime.datetime.fromtimestamp(mtime).strftime('%m-%d')
            sessions.append((label, name, date, tools_n, inject_bytes, out_tok,
                             max_inject, tools, read_paths))
    return sessions


# ─── 汇总输出 ───────────────────────────────────────────────────────────

def fmt_kb(b):
    return f'{b // 1024:,}'

def build_section(conv, execs):
    lines = []
    lines.append('### 4.1 最近对话消耗（对话层，字节为真实值）')
    lines.append('')
    lines.append('| 会话 | 来源 | 轮数 | 用户输入KB | 助手输出KB | 累计发送MB | 每轮均值KB |')
    lines.append('|------|------|-----:|-----------:|-----------:|-----------:|-----------:|')
    if conv:
        for label, task, turns, in_b, out_b, out_tok, acc in conv:
            if turns == 0:
                continue
            avg = acc // turns // 1024
            lines.append(f'| {task} | {label} | {turns} | {fmt_kb(in_b)} | {fmt_kb(out_b)} | {acc / 1024 / 1024:.1f} | {avg:,} |')
    else:
        lines.append('| （无对话数据） | - | - | - | - | - | - |')
    lines.append('')
    lines.append('> 每轮均值 = 该会话平均每轮携带的上下文大小（真实字节），越大越该考虑开新会话。')
    lines.append('')
    lines.append('### 4.2 历史执行轨迹（执行层，旧 IDE transcript，按天聚合）')
    lines.append('')
    lines.append('| 日期 | 来源 | 会话数 | 工具调用 | 注入KB | 输出tok | 最大单次KB |')
    lines.append('|------|------|-------:|---------:|-------:|--------:|-----------:|')
    if execs:
        by_day = defaultdict(lambda: [0, 0, 0, 0, 0])  # date+label -> [n, tools, inj, out, max]
        order = []
        for label, name, date, tn, inj, ot, mx, tools, rp in execs:
            k = (date, label)
            if k not in by_day:
                order.append(k)
            d = by_day[k]
            d[0] += 1
            d[1] += tn
            d[2] += inj
            d[3] += ot
            d[4] = max(d[4], mx)
        for k in sorted(order, reverse=True):
            date, label = k
            n, tn, inj, ot, mx = by_day[k]
            lines.append(f'| {date} | {label} | {n} | {tn} | {fmt_kb(inj)} | {ot:,} | {fmt_kb(mx)} |')
    else:
        lines.append('| （无执行轨迹数据） | - | - | - | - | - | - |')
    lines.append('')
    # 工具分布
    alltools = Counter()
    for r in execs:
        alltools.update(r[7])
    if alltools:
        top = '、'.join(f'{n}×{c}' for n, c in alltools.most_common(5))
        lines.append(f'- 工具调用分布 TOP5：{top}')
    # 重复读文件
    dup = Counter()
    for r in execs:
        for p, c in r[8].items():
            if c > 3:
                dup[p] += c
    if dup:
        lines.append('')
        lines.append('- 重复读同一文件（>3 次，浪费点）：')
        for p, c in dup.most_common(5):
            lines.append(f'  - ×{c} {p}')
    return '\n'.join(lines) + '\n'

def build_advice(conv, execs):
    adv = []
    for label, task, turns, in_b, out_b, out_tok, acc in conv:
        if turns == 0:
            continue
        avg_kb = acc // turns // 1024
        if avg_kb > 150:
            adv.append(f'⚠️ 会话 {task} 每轮平均携带 {avg_kb}KB 上下文（>150KB）→ 完成子目标后主动开新会话')
        if acc > 50 * 1024 * 1024:
            adv.append(f'⚠️ 会话 {task} 累计发送 {acc / 1024 / 1024:.0f}MB → 长会话未拆分，历史重复计费')
    dup = Counter()
    for r in execs:
        for p, c in r[8].items():
            if c > 5:
                dup[p] += c
    if dup:
        p, c = dup.most_common(1)[0]
        adv.append(f'⚠️ 同一文件被反复读取 ×{c}（如 {os.path.basename(p)}）→ 先想清楚要读哪个区间，用 start_line/end_line 一次读完')
    big = [(r[0], r[6]) for r in execs if r[6] > 100 * 1024]
    if big:
        mx = max(big, key=lambda x: x[1])
        adv.append(f'⚠️ 单次工具返回最大 {mx[1] // 1024}KB（{mx[0][:20]}）→ 大文件全量读会一次性灌入上下文，改分段读')
    return adv

def build_summary(conv, execs):
    parts = []
    if conv:
        total_acc = sum(r[6] for r in conv)
        total_turns = sum(r[2] for r in conv)
        avg_kb = (total_acc // total_turns // 1024) if total_turns else 0
        parts.append(f'近期对话 {len(conv)} 个会话，每轮平均上下文 ~{avg_kb}KB')
    if execs:
        tn = sum(r[3] for r in execs)
        inj = sum(r[4] for r in execs)
        parts.append(f'历史执行轨迹共 {len(execs)} 个会话：工具调用 {tn} 次 / 注入 {fmt_kb(inj)}KB')
    if not parts:
        parts.append('暂无会话数据')
    return ' | '.join(parts)

def main():
    conv = scan_conversations()
    execs = scan_transcripts()
    print('<<<SECTION')
    print(build_section(conv, execs), end='')
    print('<<<ADVICE')
    for a in build_advice(conv, execs):
        print(a)
    print('<<<SUMMARY')
    print(build_summary(conv, execs))

if __name__ == '__main__':
    main()
