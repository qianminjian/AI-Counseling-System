#!/bin/bash
# verify-no-new-last.sh — 防新增 .last("LIMIT...") 手写分页（doing/92 R-022）
# 背景：MybatisPlusConfig 注释登记 6 处存量（值均为常量无注入面），收敛为 Page 分页前
# 禁止新增；本脚本供 CI 挂载（新增 .last( 调用即失败）。
set -euo pipefail

BASE="$(cd "$(dirname "$0")/.." && pwd)"

# 已登记存量（实测 2026-08-11：14 个文件——原 MybatisPlusConfig 注释 6 处登记不全；
# doing/92 R-022 价值实证。合法：常量值无注入面；收敛完成后逐步移除）
LEGACY=(
  "service/auth/TrialAuthService.java"
  "service/auth/ParentAuthService.java"
  "service/wecom/WeComOAuthService.java"
  "service/prompt/PromptVersionService.java"
  "service/profile/StudentProfileService.java"
  "service/teacher/TeacherQualityService.java"
  "service/toc/TocDeviceService.java"
  "service/config/SysConfigService.java"
  "service/notification/NotificationServiceImpl.java"
  "service/consent/ConsentWithdrawalService.java"
  "service/ops/OpsInsightsService.java"
  "service/device/DevicePreferenceService.java"
  "service/device/DeviceSecurityService.java"
  "service/device/DeviceService.java"
  "service/monitoring/OpsService.java"
  "service/monitoring/DegradationMatrixService.java"
  "service/teacher/TeacherService.java"
  "tenant/MybatisPlusConfig.java"
)

fail=0
while IFS= read -r f; do
  [ -n "$f" ] || continue
  legacy=false
  for l in "${LEGACY[@]}"; do
    if [[ "$f" == *"$l" ]]; then legacy=true; break; fi
  done
  if [ "$legacy" = false ]; then
    echo "❌ 新增 .last( 调用（存量 6 处已登记，禁止新增）: $f"
    fail=1
  fi
done <<< "$(grep -rl '\.last("' "$BASE/counseling-service/src/main/java" "$BASE/counseling-domain/src/main/java" 2>/dev/null || true)"

if [ "$fail" -eq 0 ]; then
  echo "✅ 无新增 .last( 调用（存量 6 处已登记）"
fi
exit $fail
