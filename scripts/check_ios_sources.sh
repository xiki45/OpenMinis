#!/usr/bin/env bash
#
# scripts/check_ios_sources.sh — iOS 源码预检（不需要 Xcode / iOS SDK）
#
# 只做两件在 Android 机器上也能做完的事：
#
#   1) 工程成员检查：改动的 .swift 必须出现在 Minis.xcodeproj/project.pbxproj
#      里，否则它不属于任何 target —— 等于从来没有被编译过。
#      这正是 SystemPromptProseStore.swift / SystemPromptSettingsView.swift
#      的真实故障：文件写了，编译错误一直在，但没人编译过。
#
#      例外：Xcode 16 的 PBXFileSystemSynchronizedRootGroup（同步组）会自动
#      收录其目录下的文件，无需逐条登记。这些目录从 pbxproj 里读出来跳过。
#
#   2) 语法解析：swiftc -parse。Command Line Tools 自带的 swiftc 不加载 iOS
#      SDK 也能做纯语法检查。
#
# 本地查不到的东西：类型与符号错误（如 "Cannot find X in scope"）。
# 那需要 iOS SDK 与完整构建，由 .github/workflows/ios-compile.yml 兜。
#
# 用法：
#   scripts/check_ios_sources.sh                 # 基线 origin/main
#   scripts/check_ios_sources.sh upstream/main   # 指定基线
#   scripts/check_ios_sources.sh --all           # 全量检查（只报未登记文件）
#
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PBXPROJ="$ROOT/src/ios/Minis.xcodeproj/project.pbxproj"
cd "$ROOT" || exit 1

if [ ! -f "$PBXPROJ" ]; then
  echo "错误：找不到 $PBXPROJ" >&2
  exit 1
fi

# 同步组目录名（Xcode 16 synchronized groups）——这些目录下的 .swift 由 Xcode
# 自动收录，不需要在 pbxproj 里登记。
SYNC_DIRS="$(grep 'PBXFileSystemSynchronizedRootGroup;' "$PBXPROJ" \
  | sed -E 's/.*path = ([A-Za-z0-9_.]+);.*/\1/' | sort -u | tr '\n' ' ')"

is_synced_dir() {
  f="$1"; d=""
  for d in $SYNC_DIRS; do
    case "/$f" in */"$d"/*) return 0 ;; esac
  done
  return 1
}

# 已知「故意不进 target」的源文件：文档式示例，无人引用，编进 target 反而会与
# 真实实现产生重名符号。
ALLOWLIST=" src/ios/ISHCommandExecutionExample.swift "

is_allowlisted() {
  case "$ALLOWLIST" in *" $1 "*) return 0 ;; *) return 1 ;; esac
}

MODE_ALL=0
BASE_REF="origin/main"
if [ "${1:-}" = "--all" ]; then
  MODE_ALL=1
else
  BASE_REF="${1:-origin/main}"
fi

fail=0

# ---------- 1. 成员检查 ----------
if [ "$MODE_ALL" = "1" ]; then
  echo "== 成员检查（全量）=="
  FILES="$(git ls-files 'src/ios/**/*.swift' 'src/ios/*.swift')"
else
  MERGE_BASE="$(git merge-base "$BASE_REF" HEAD 2>/dev/null)"
  if [ -z "$MERGE_BASE" ]; then
    echo "警告：无法解析基线 '$BASE_REF'，改为检查工作区改动" >&2
    FILES="$(git diff --name-only --diff-filter=ACMR -- src/ios | grep -E '\.swift$')"
  else
    FILES="$(
      {
        git diff --name-only --diff-filter=ACMR "$MERGE_BASE"...HEAD -- src/ios
        git diff --name-only --diff-filter=ACMR -- src/ios
        git ls-files --others --exclude-standard -- src/ios
      } | grep -E '\.swift$' | sort -u)"
  fi
  echo "== 成员检查（相对 ${BASE_REF}）=="
fi

unregistered=0
for f in $FILES; do
  [ -f "$f" ] || continue
  if is_synced_dir "$f"; then continue; fi
  if is_allowlisted "$f"; then continue; fi
  base="$(basename "$f")"
  if ! grep -q -- "$base" "$PBXPROJ"; then
    echo "  ✗ 未登记进工程: $f"
    unregistered=$((unregistered + 1))
    fail=1
  fi
done
if [ "$unregistered" -eq 0 ]; then
  echo "  ✓ 所有检查范围内的 .swift 都已登记"
fi

# ---------- 2. 语法解析 ----------
if [ "$MODE_ALL" = "0" ]; then
  echo "== 语法解析 (swiftc -parse) =="
  if ! command -v swiftc >/dev/null 2>&1; then
    echo "  跳过：本机没有 swiftc"
  else
  parse_fail=0
    for f in $FILES; do
      [ -f "$f" ] || continue
      if ! err="$(swiftc -parse "$f" 2>&1)"; then
        echo "  ✗ $f"
        printf '%s\n' "$err" | head -6 | sed 's/^/      /'
        parse_fail=$((parse_fail + 1))
        fail=1
      fi
    done
    [ "$parse_fail" -eq 0 ] && echo "  ✓ 无语法错误" || true
  fi
fi

if [ "$fail" -ne 0 ]; then
  echo
  echo "预检失败。若只是「未登记进工程」，把文件加入 Minis.xcodeproj 对应 group 即可"
  echo "（或在仓库机器上无法打开 Xcode 时，按 SoulStore.swift 的模式在 project.pbxproj"
  echo " 里补 PBXBuildFile / PBXFileReference / group children / Sources 四处条目）。"
  exit 1
fi

echo
echo "✓ 预检通过（类型与符号层由 CI 的 xcodebuild 负责）"
exit 0
