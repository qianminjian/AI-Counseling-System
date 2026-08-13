#!/bin/bash
# token-usage-report.sh - 每日 token 消耗监控分析（2026-08-13 起）
#
# 用途：量化项目各层（规则/文档/会话）的 token 占用分布，给出优化建议
# 用法：bash scripts/token-usage-report.sh [--plain]   （默认输出报告并落盘 logs/token-report/）
# 输出：终端摘要 + logs/token-report/token-<ts>.md
#
# 口径说明（估算，非精确计费）：
#   - 中文文本：1 汉字 ≈ 1~2 tokens，UTF-8 3 字节/字 → 取 bytes/2 为中间估算
#   - 英文/代码：4 字节 ≈ 1 token → 取 bytes/4
#   - 混合文件按中文字符占比加权（脚本内自动估算）
#
# 依赖：python3（仅用于 JSON 解析与 token 估算，无第三方库）

set -u

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="$PROJECT_ROOT/logs/token-report"
TS="$(date +%Y%m%d-%H%M%S)"
REPORT_FILE="$REPORT_DIR/token-${TS}.md"
PLAIN=0
[ "${1:-}" = "--plain" ] && PLAIN=1

mkdir -p "$REPORT_DIR"

# ─── 工具函数：估算 token 数 ─────────────────────────────────────────────
# 输入：文件路径；输出：估算 token 数（整数）
est_tokens() {
    local f="$1"
    python3 - "$f" << 'PYEOF'
import sys, re
p = sys.argv[1]
try:
    raw = open(p, 'rb').read()
    text = raw.decode('utf-8', errors='ignore')
except Exception:
    print(0); sys.exit()
cjk = len(re.findall(r'[\u4e00-\u9fff\u3000-\u303f\uff00-\uffef]', text))
total_chars = len(text)
if total_chars == 0:
    print(0); sys.exit()
cjk_ratio = cjk / total_chars
bytes_len = len(raw)
# 中文占比高 → bytes/2（1 汉字≈1.5token 取中间），英文为主 → bytes/4
est = bytes_len / (2 if cjk_ratio > 0.3 else 4)
print(int(est))
PYEOF
}

# ─── 1. 规则层统计 ─────────────────────────────────────────────────────
RULES_DIR="$PROJECT_ROOT/.qoder/rules"
declare -a ALWAYS_RULES=(); declare -a SPECIFIC_RULES=(); declare -a MD_RULES=(); declare -a MANUAL_RULES=()
always_tok=0; specific_tok=0; md_tok=0; manual_tok=0

while IFS= read -r f; do
    trig="$(grep -m1 '^trigger:' "$f" | awk '{print $2}')"
    t="$(est_tokens "$f")"
    case "$trig" in
        always)   ALWAYS_RULES+=("$(basename "$f")"); always_tok=$((always_tok+t));;
        specific) SPECIFIC_RULES+=("$(basename "$f")"); specific_tok=$((specific_tok+t));;
        manual)   MANUAL_RULES+=("$(basename "$f")"); manual_tok=$((manual_tok+t));;
        *)        MD_RULES+=("$(basename "$f")"); md_tok=$((md_tok+t));;
    esac
done < <(find "$RULES_DIR" -maxdepth 1 -name '*.md' | sort)

AGENTS_TOK="$(est_tokens "$PROJECT_ROOT/AGENTS.md")"
# 每轮必注入 = always 规则 + AGENTS.md；编码轮额外 = specific + model-decision
every_turn_tok=$((always_tok + AGENTS_TOK))
coding_turn_tok=$((every_turn_tok + specific_tok + md_tok))

# ─── 2. 文档层统计（design/ 与根 md）───────────────────────────────────
declare -a BIG_DOCS=()      # >50KB 大文档
declare -a NO_INDEX_DOCS=() # >50KB 且无分段索引头
doc_total_tok=0; doc_total_bytes=0

while IFS= read -r f; do
    sz="$(wc -c < "$f" | tr -d ' ')"
    t="$(est_tokens "$f")"
    doc_total_tok=$((doc_total_tok+t)); doc_total_bytes=$((doc_total_bytes+sz))
    if [ "$sz" -gt 51200 ]; then
        BIG_DOCS+=("$(basename "$f")|$sz|$t")
        grep -q '📑 分段索引' "$f" || NO_INDEX_DOCS+=("$(basename "$f")|$sz")
    fi
done < <(find "$PROJECT_ROOT/design" -maxdepth 1 -name '*.md' | sort)

for f in "$PROJECT_ROOT"/*.md; do
    [ -f "$f" ] || continue
    sz="$(wc -c < "$f" | tr -d ' ')"
    t="$(est_tokens "$f")"
    doc_total_tok=$((doc_total_tok+t)); doc_total_bytes=$((doc_total_bytes+sz))
    if [ "$sz" -gt 51200 ]; then
        BIG_DOCS+=("$(basename "$f")|$sz|$t")
        grep -q '📑 分段索引' "$f" || NO_INDEX_DOCS+=("$(basename "$f")|$sz")
    fi
done

# ─── 3. 会话层统计（conversation-history）──────────────────────────────
# 主项目 + dev 副本（审计误入副本曾发生，一并监控）
declare -a SESSION_FILES=()
while IFS= read -r f; do SESSION_FILES+=("$f"); done < \
    <(find "$HOME/.qoder-cn/cache/projects/" -maxdepth 2 -name 'conversation-history' -type d 2>/dev/null \
      | grep -i 'AI-Counseling-System' | while read -r d; do find "$d" -name '*.jsonl' 2>/dev/null; done)

session_count=${#SESSION_FILES[@]}
session_total_bytes=0; session_total_tok=0; long_session_count=0
declare -a TOP_SESSIONS=()
if [ "$session_count" -gt 0 ]; then
    for f in "${SESSION_FILES[@]}"; do
        sz="$(wc -c < "$f" | tr -d ' ')"
        t="$(est_tokens "$f")"
        session_total_bytes=$((session_total_bytes+sz)); session_total_tok=$((session_total_tok+t))
        [ "$sz" -gt 204800 ] && long_session_count=$((long_session_count+1))
        TOP_SESSIONS+=("$(basename "$f" .jsonl)|$sz|$t")
    done
fi
# TOP5 会话按字节降序（bash 3.2 无 mapfile，用 while read 收集）
declare -a TOP5=()
while IFS= read -r row; do TOP5+=("$row"); done < \
    <(printf '%s\n' "${TOP_SESSIONS[@]}" | sort -t'|' -k2 -rn | head -5)

# ─── 4. 习惯体检与建议 ─────────────────────────────────────────────────
declare -a ADVICE=()
[ "${#NO_INDEX_DOCS[@]}" -gt 0 ] && ADVICE+=("⚠️ ${#NO_INDEX_DOCS[@]} 个大文档(>50KB)缺少📑分段索引头：$(printf '%s ' "${NO_INDEX_DOCS[@]%%|*}")→ 加索引头（参考 TASK-TRACKER.md 顶部格式）")
[ "$long_session_count" -gt 0 ] && ADVICE+=("⚠️ $long_session_count 个会话文件 >200KB（长会话未拆分信号）→ 完成子目标后主动开新会话，用 session-summary.md 传递状态")
# 检测 AGENTS.md 是否混入规则正文（纯导航版不含红线正文句“必须停下来问”）
if grep -q '必须停下来问' "$PROJECT_ROOT/AGENTS.md" 2>/dev/null; then
    ADVICE+=("⚠️ AGENTS.md 混入规则正文（检测到红线原文句）→ 收敛回纯导航，正文只留 .qoder/rules/")
fi
if [ "$session_total_bytes" -gt 0 ]; then
    avg=$((session_total_bytes / session_count))
    [ "$avg" -gt 102400 ] && ADVICE+=("⚠️ 会话平均 $((avg/1024))KB 偏大 → 控制单轮 Read 大文件数（≤5 个），优先 Grep/Glob")
fi
[ "${#ADVICE[@]}" -eq 0 ] && ADVICE+=("✅ 未发现明显浪费信号，保持现有习惯")

# ─── 5. 输出报告（bash 3.2：变量用 ${var} 防全角字符粘连）─────────────────
{
cat << EOF
# Token 消耗监控日报 - $(date '+%Y-%m-%d %H:%M')

## 一、规则层（.qoder/rules/ + AGENTS.md）

| 触发类型 | 文件数 | 估算 tokens |
|---------|-------|------------|
| always（每轮必注入） | ${#ALWAYS_RULES[@]} | ${always_tok} |
| AGENTS.md（每轮必注入） | 1 | ${AGENTS_TOK} |
| specific（按文件 glob） | ${#SPECIFIC_RULES[@]} | ${specific_tok} |
| model-decision（AI 判断） | ${#MD_RULES[@]} | ${md_tok} |
| manual（手动 @） | ${#MANUAL_RULES[@]} | ${manual_tok} |

- **每轮固定开销 ≈ ${every_turn_tok} tokens**
- **编码任务轮 ≈ ${coding_turn_tok} tokens**（+specific +model-decision）

## 二、文档层（design/ + 根目录 md）

- 文档总规模：$((doc_total_bytes/1024)) KB ≈ ${doc_total_tok} tokens
- >50KB 大文档 ${#BIG_DOCS[@]} 个：
EOF
printf '%s\n' "${BIG_DOCS[@]}" | sort -t'|' -k2 -rn | head -8 | while IFS='|' read -r n s t; do
    printf -- "- %-40s %4d KB ≈ %6d tokens\n" "$n" $((s/1024)) "$t"
done

cat << EOF

## 三、会话层（conversation-history）

- 会话文件数：${session_count}；总 $((session_total_bytes/1024)) KB ≈ ${session_total_tok} tokens
- 超长会话（>200KB）：${long_session_count} 个
- TOP5 会话：
EOF
if [ "${#TOP5[@]}" -gt 0 ]; then
    for row in "${TOP5[@]}"; do
        IFS='|' read -r n s t <<< "$row"
        printf -- "- %-40s %4d KB ≈ %6d tokens\n" "$n" $((s/1024)) "$t"
    done
else
    echo "- （无会话历史数据）"
fi

cat << EOF

## 四、习惯体检与优化建议

EOF
for a in "${ADVICE[@]}"; do echo "- $a"; done

cat << EOF

## 五、今日优化动作（供 AI 执行参考）

1. 规则层：优先精简 model-decision 组中触发频率高但内容低频的规则
2. 文档层：给缺索引头的大文档补📑分段索引；读取时用 start_line/end_line
3. 会话层：长会话主动拆分；单轮 Read 大文件 ≤5 个
4. 复查：对照 AGENTS.md §token 使用纪律执行

---
_生成：scripts/token-usage-report.sh | 数据时间 $(date '+%F %T')_
EOF
} > "$REPORT_FILE"

# ─── 终端摘要 ───────────────────────────────────────────────────────────
echo "=== Token 监控日报 $(date '+%F %T') ==="
echo "规则层: 每轮固定 ~$every_turn_tok tok | 编码轮 ~$coding_turn_tok tok"
echo "文档层: $((doc_total_bytes/1024)) KB ≈ $doc_total_tok tok | 大文档 ${#BIG_DOCS[@]} 个(缺索引 ${#NO_INDEX_DOCS[@]})"
echo "会话层: $session_count 文件 / $((session_total_bytes/1024)) KB ≈ $session_total_tok tok | 超长 $long_session_count"
echo "建议:"
for a in "${ADVICE[@]}"; do echo "  - $a"; done
echo "报告: $REPORT_FILE"

exit 0
