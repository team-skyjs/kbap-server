#!/usr/bin/env bash
#
# Jira 태스크 초안을 Codex 로 한국어 윤문한다.
# cmux 새 탭(new-workspace --command)에서 실행되도록 설계됐다.
#
# 사용:  codex-polish.sh <input.md> <output.md> <done-marker>
#   input.md     윤문 지시 + 원문 초안 (윤문 결과는 <<<BEGIN>>>..<<<END>>> 마커로 감싸라고 지시)
#   output.md    codex 최종 메시지가 기록될 파일 (-o 로 직접 기록)
#   done-marker  완료 시 생성되는 마커 파일 (호출자가 이 파일 존재로 완료 폴링)
#
# 종료해도 반드시 done-marker 를 남겨 호출자가 무한대기하지 않게 한다.

set -uo pipefail

INPUT="${1:?input file required}"
OUTPUT="${2:?output file required}"
DONE="${3:?done marker required}"

trap 'echo "exit=$?" > "$DONE"' EXIT

CODEX_BIN="$(command -v codex || true)"
if [ -z "$CODEX_BIN" ] && [ -x /opt/homebrew/bin/codex ]; then
  CODEX_BIN=/opt/homebrew/bin/codex
fi
if [ -z "$CODEX_BIN" ]; then
  echo "codex CLI not found on PATH" > "$OUTPUT"
  exit 127
fi

"$CODEX_BIN" exec --skip-git-repo-check -s read-only --color never \
  -o "$OUTPUT" "$(cat "$INPUT")" < /dev/null

echo
echo "----- Codex 윤문 완료. output 파일이 기록됐습니다. 이 탭은 닫아도 됩니다. -----"
