---
name: create-jira-task-with-codex
description: "Jira(KB) 태스크를 등록하기 전, 초안 본문(Background·DoD)을 cmux 분할 창(cmd+d식 split)에서 Codex 로 한국어 윤문받아 before/after 확인 후 등록할 때 사용. '코덱스로 윤문해서 지라 등록', '초안 다듬어서 태스크 등록', 'codex 로 문장 다듬고 jira', '어색한 한국어 고쳐서 등록', 'polish and create jira task' 요청 시 사용. 윤문 없이 바로 등록은 create-jira-task 를 쓴다."
---

# Codex 윤문 후 Jira 태스크 등록

Jira 태스크를 등록하기 **직전**, 초안 본문을 cmux 분할 창(cmd+d식 split)에서 Codex(`codex exec`)로 넘겨 **의미는 그대로 두고 어색한 한국어만** 다듬은 결과를 되받아, before/after 확인 후 등록하는 절차. 등록 자체는 **REQUIRED SUB-SKILL: create-jira-task** 를 그대로 재사용한다 — 이 스킬은 그 앞단에 "Codex 윤문 + 확인" 단계를 얹은 것이다.

## 언제 사용 / 사용 안 함

- **사용**: 초안 문장이 번역투·비문·어색함이 있어 등록 전 다듬고 싶을 때.
- **사용 안 함**: 문장을 다듬을 필요가 없거나 급할 때 → 바로 `create-jira-task`. Codex/cmux 가 없는 환경 → 바로 `create-jira-task`.

## 사전 조건

- **`codex` CLI** 설치(`codex --version`). 로그인/인증이 되어 있어야 `codex exec` 가 응답한다.
- **cmux 앱** 실행 중(`cmux --version`). 분할 창은 `cmux new-split`(cmd+d 와 동일 — 현재 workspace 안에서 pane 분할)로 연다. Claude 의 Bash 는 cmux surface 안에서 돌아 `$CMUX_SURFACE_ID` 가 이미 잡혀 있으므로 split 은 현재 창에서 갈라진다.
- 등록 단계 도구(Atlassian MCP 등)는 `create-jira-task` 사전 조건을 따른다.
- 작업 파일은 **세션 scratchpad 디렉터리** 아래에 둔다(시스템 프롬프트의 scratchpad 경로). 이하 예시는 `$WORK=<scratchpad>/codex-jira`.

## 절차 (체크리스트)

1. **초안 작성** — `create-jira-task` 의 "본문 규약"대로 Background(개조식) + DoD(체크박스) 초안을 **일반 텍스트/마크다운**으로 먼저 쓴다(아직 ADF 아님). 이 텍스트가 윤문 대상이다.

2. **작업 파일 준비** — `$WORK` 디렉터리를 만들고 경로를 정한다: `input.md`(윤문 지시+원문), `output.md`(Codex 결과), `done`(완료 마커). 이전 잔여물 제거(`rm -f`).

3. **`input.md` 작성** — Codex 에 줄 지시문 + 원문을 함께 넣는다. 핵심 제약을 명시한다:
   - 의미·사실·수치·고유명사·구조(제목/불릿 개수/DoD 항목 수)를 **절대 바꾸지 말 것**.
   - 어색한 표현·번역투·비문만 자연스러운 한국어로 다듬을 것.
   - 출력은 반드시 **`<<<BEGIN>>>` 와 `<<<END>>>` 마커 사이에 다듬은 결과만**. 그 외 머리말·설명·코드펜스 금지.
   - 원문은 `[원문]` 아래에 그대로 붙인다.

4. **cmd+d식 split 에서 Codex 실행** — 현재 pane 을 옆으로 쪼갠 새 surface 에 스크립트 실행 명령을 보낸다(별도 사이드바 세션이 아니라 **지금 workspace 안** 분할 창). `SKILL_DIR` 은 이 스킬 폴더:
   ```bash
   SURFACE=$(cmux new-split right --focus true --json \
     | sed -n 's/.*"surface_ref" *: *"\([^"]*\)".*/\1/p')
   cmux send --surface "$SURFACE" \
     "bash '$SKILL_DIR/codex-polish.sh' '$WORK/input.md' '$WORK/output.md' '$WORK/done'\n"
   ```
   - `new-split <right|down>` 은 `$CMUX_SURFACE_ID`(현재 창)에서 갈라진다. `--json` 의 `surface_ref` 가 명령을 보낼 대상.
   - `send` 는 텍스트 끝 `\n` 으로 Enter 까지 보낸다(별도 `send-key enter` 불필요).
   - 스크립트는 `codex exec --skip-git-repo-check -s read-only -o output.md "<input>" < /dev/null`(읽기전용·stdin 차단이 hang 방지 핵심) 실행 후 `done` 마커를 남긴다.

5. **완료 대기(폴링)** — `done` 마커가 생길 때까지 기다린다. 포그라운드 `sleep` 은 막혀 있으므로 **`Monitor` 툴로 `test -f "$WORK/done"` 을 조건으로 until-대기**하거나, 대기 명령을 `run_in_background` 로 돌린다. 타임아웃(예: 180초) 초과 시 6번 실패 처리.

6. **윤문본 회수·추출** — `output.md` 에서 마커 사이만 뽑는다:
   ```bash
   awk '/<<<BEGIN>>>/{f=1;next} /<<<END>>>/{f=0} f' "$WORK/output.md"
   ```
   비어 있거나 `codex ... not found`/`exit=` 가 0이 아니면 실패로 본다(→ 실패 대응). 회수했으면 `cmux close-surface --surface "$SURFACE"` 로 분할 창을 닫아 정리해도 된다(원치 않으면 남겨 둬도 무방).

7. **before/after 확인 게이트** — 사용자에게 **원본 초안 vs 윤문본**을 나란히 보여주고 승인을 받는다. 사용자가 수정/부분반영을 원하면 반영한다. **승인 전에는 등록하지 않는다.**

8. **등록** — 승인된 윤문본을 본문으로 삼아 **`create-jira-task` 절차를 그대로 수행**한다(accountId 확인 → 이슈 생성 → ADF 본문·레이블·할당 → 보고). 스프린트/에픽 등 추가 지정이 있으면 그 절차에 포함한다.

## 실패 대응 (등록을 막지 않는다)

- `codex`/`cmux` 없음, hang, 타임아웃, 마커 추출 실패 → **윤문을 건너뛰고 원본 초안 그대로 `create-jira-task` 로 등록**한다. 진단이 필요하면 `cat "$WORK/output.md"` 또는 분할 창 화면 `cmux read-screen --surface "$SURFACE" --scrollback` 로 확인.
- 윤문이 의미를 바꾼 것으로 보이면(7번에서 발견) 해당 부분은 **원문을 채택**한다 — 윤문은 표현만 손대는 것이 원칙.

## 흔한 함정

- **stdin 미차단 시 hang**: `codex exec` 는 stdin 이 열려 있으면 "Reading additional input from stdin..." 로 멈춘다. 반드시 `< /dev/null`(스크립트가 처리).
- **마커 없이 stdout 파싱**: Codex 최종 메시지 앞뒤에 잡음이 섞일 수 있어 마커 추출이 안전. `-o output.md` 는 최종 메시지만 파일로 준다.
- **포그라운드 sleep 폴링**: 막혀 있다. `Monitor` 또는 백그라운드로 대기.
- **`surface_ref` 파싱 실패**: `new-split --json` 결과가 비면 `$SURFACE` 가 빈 값이 된다 → `send` 대상이 없다. 파싱 후 `[ -n "$SURFACE" ]` 확인, 실패 시 실패 대응으로.
- **`new-workspace` 와 혼동**: 이 스킬은 `new-split`(현재 workspace 안 분할, cmd+d)이다. `new-workspace` 는 사이드바에 별도 세션을 만들어 무겁고 분리된다 — 쓰지 말 것.
- **윤문본을 확인 없이 등록**: 이 스킬의 존재 이유가 before/after 확인이다. 7번 게이트를 건너뛰지 말 것.
- **경로에 공백/한글**: 모든 경로 인자를 작은따옴표로 감싼다(`cmux send` 로 보내는 명령 문자열 안에서도).
