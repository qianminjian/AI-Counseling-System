#!/usr/bin/env bash
#
# 中文字体子集化脚本：把全量中文字体（10-20MB）裁剪成只含常用字的 woff2（~1MB/字重）
#
# 为什么需要：移动端 H5 不能加载全量中文字体；心理对话词汇为日常用语，
#             子集到 GB2312 常用字（6763 字，覆盖 99.9% 日常交流）即可。
#
# 使用步骤：
#   1. 安装依赖：   pip3 install fonttools brotli
#   2. 下载源泉圆体（GenSenRounded，OFL 开源协议，可免费商用）：
#        GitHub: https://github.com/ButTaiwan/GenSenRounded
#       下载 Release 中的 TTF，取出 Regular（R）与 Bold（B）两个字重
#   3. 运行（把两个源 TTF 路径作为参数传入）：
#        bash scripts/subset-font.sh /path/to/GenSenRounded-R.ttf /path/to/GenSenRounded-B.ttf
#   4. 产物自动输出到 frontend/student-h5/public/fonts/，
#      index.css 的 @font-face 会自动加载（font-display: swap，缺失时平滑回退系统字体）。
#
set -euo pipefail

REGULAR_SRC="${1:-}"
BOLD_SRC="${2:-}"

OUT_DIR="$(cd "$(dirname "$0")/../frontend/student-h5/public/fonts" && pwd)"
CHARS_FILE="$(mktemp)"
trap 'rm -f "$CHARS_FILE"' EXIT

command -v pyftsubset >/dev/null 2>&1 || { echo "❌ 未找到 pyftsubset，请先执行: pip3 install fonttools brotli"; exit 1; }
[ -n "$REGULAR_SRC" ] && [ -f "$REGULAR_SRC" ] || { echo "❌ 请传入 Regular 源字体路径（参数1），如: bash scripts/subset-font.sh GenSenRounded-R.ttf GenSenRounded-B.ttf"; exit 1; }
[ -n "$BOLD_SRC" ] && [ -f "$BOLD_SRC" ] || { echo "❌ 请传入 Bold 源字体路径（参数2）"; exit 1; }

mkdir -p "$OUT_DIR"

# 生成常用字符表：GB2312 一级+二级汉字（6763 字）+ ASCII 可打印字符 + 常用中文标点
python3 - "$CHARS_FILE" <<'PY'
import sys
chars = set()
for section in range(16, 88):          # GB2312 第 16-87 区为汉字
    for position in range(1, 95):      # 每区 94 个位
        try:
            chars.add(bytes([0xA0 + section, 0xA0 + position]).decode('gb2312'))
        except (UnicodeDecodeError, ValueError):
            pass
extra = ''.join(chr(c) for c in range(0x20, 0x7F))  # ASCII 可打印字符
extra += '，。！？；：""''（）《》【】、—…·￥％＆＊＋－．／'
chars.update(extra)
with open(sys.argv[1], 'w', encoding='utf-8') as f:
    f.write(''.join(sorted(chars)))
print(f'✅ 字符表已生成：{len(chars)} 个字符')
PY

echo "→ 子集化 Regular: $REGULAR_SRC"
pyftsubset "$REGULAR_SRC" --text-file="$CHARS_FILE" --flavor=woff2 \
  --output-file="$OUT_DIR/GenSenRounded-Regular.woff2"

echo "→ 子集化 Bold: $BOLD_SRC"
pyftsubset "$BOLD_SRC" --text-file="$CHARS_FILE" --flavor=woff2 \
  --output-file="$OUT_DIR/GenSenRounded-Bold.woff2"

echo ""
echo "✅ 完成，产物如下："
ls -lh "$OUT_DIR"/*.woff2
echo ""
echo "woff2 已输出到 frontend/student-h5/public/fonts/，@font-face 将自动加载。"
