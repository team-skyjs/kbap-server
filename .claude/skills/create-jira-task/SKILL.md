---
name: create-jira-task
description: "kbap/kbap 백엔드 작업을 Jira(KB 프로젝트) 태스크로 등록하는 절차 — 프로젝트 KB·유형 작업·BE 레이블·실행자 본인 할당을 표준으로, 본문은 ADF(개조식 Background + 체크박스 DoD)로 자체 완결형 작성한다. '지라에 태스크로 등록', 'jira 이슈 만들어', '이거 태스크로 빼줘', 'create jira task', '백로그에 등록' 요청 시 사용."
---

# Jira 태스크 등록 (KB 프로젝트)

현재 프로젝트(kbap-server / kbap-server)의 백엔드 작업을 **Jira KB 프로젝트의 태스크**로 등록하는 표준 절차. 팀 공통 규약(프로젝트·레이블·할당·본문 서식)을 강제해 누가 등록해도 일관되게 만든다.

> **등록 전 한국어 윤문이 필요하면** — 초안 본문이 어색하거나 번역투일 때는, 이슈 생성 전에 `create-jira-task-with-codex` 스킬로 cmux 분할 창(cmd+d식 split)에서 Codex 윤문 → before/after 확인을 거친 뒤 그 결과를 본문으로 삼아 아래 절차를 수행한다. 윤문이 불필요하면 이 스킬을 그대로 쓴다.

## 사전 조건

- **Atlassian MCP 서버가 연결**되어 있어야 한다(`/mcp` 에서 `atlassian` 이 `✓ Connected`). 미연결이면 먼저 붙인다:
  ```bash
  claude mcp add --transport http atlassian https://mcp.atlassian.com/v1/mcp
  ```
  그다음 세션에서 `/mcp` → `atlassian` → Authenticate(브라우저 OAuth). 팀 공유는 `--scope project` 로 추가하면 `.mcp.json` 이 생겨 레포로 공유된다(각자 OAuth 로그인).
- 도구 스키마는 지연 로드 — `ToolSearch` 로 `mcp__atlassian__*` 를 불러온 뒤 호출한다.

## 프로젝트 기본값 (고정)

| 항목 | 값 |
|------|-----|
| Jira 사이트 | `simhani1.atlassian.net` (cloudId `50957656-c97c-4cc0-b1ca-f209cef7d5c9`) |
| 프로젝트 | `KB` (K-Bap) |
| 이슈 유형 | `작업` (Task). 버그면 `버그`, 큰 단위면 `스토리`/`에픽` |
| 제목 접두어 | `[BE] ` — 모든 summary 앞에 붙인다(예: `[BE] food 상세조회 API 응답 동결`) |
| 레이블 | `BE` (백엔드) |
| 할당자 | **실행자 본인**(이슈를 만드는 사람) |
| 스프린트 | **활성 스프린트에 자동 추가** (`customfield_10020`) |
| 에픽 | **매번 사용자에게 확인** — `parent` 필드로 지정(팀 관리 프로젝트) |
| SP | **Claude 가 추산해 제안 → 사용자 확인** (`customfield_10016`, **최대 5점**) |

> cloudId 가 바뀌었거나 확실치 않으면 `getAccessibleAtlassianResources` 로 재확인한다.

## 절차 (체크리스트)

1. **실행자 본인 accountId 확인** — `atlassianUserInfo` 를 호출해 현재 인증된 사용자의 `account_id` 를 얻는다(사람마다 다르므로 하드코딩하지 말 것 — 각자 자기 자신에게 할당).

2. **에픽 확인 + SP 제안 (사용자 인터랙션 — 이슈 생성 전에 한 번에)**:
   - **에픽 후보 조회** — `searchJiraIssuesUsingJql`: `project = KB AND issuetype = 에픽 AND statusCategory != Done` (fields: `summary`). 
   - **SP 추산** — 작업 범위를 보고 팀 스케일로 추산한다: **S(1) 반나절 이내 · M(2) 1일 · L(3) 2일 · XL(5) 그 이상**. **최대 5점** — 5점이 나오면 태스크 분할을 함께 제안한다.
   - `AskUserQuestion` **한 번**으로 에픽 선택(후보 + "에픽 없음")과 SP 확인(추산값을 추천 옵션으로)을 같이 묻는다.

3. **이슈 생성** — `createJiraIssue`:
   - `cloudId` = 위 값, `projectKey` = `KB`, `issueTypeName` = `작업`
   - `summary` = **`[BE] ` 접두어 + 한 줄 제목**(무엇을 하는지 명확히). 접두어는 예외 없이 붙인다.
   - 본문은 4단계에서 ADF 로 넣으므로, 생성 시엔 `summary` 만 줘도 된다(혹은 `contentFormat:"adf"` 로 바로 넣기).

4. **본문(ADF)·레이블·할당·스프린트·에픽·SP 지정** — `editJiraIssue`(`contentFormat:"adf"`):
   - `fields.description` = 아래 "본문 규약"의 ADF 문서
   - `fields.labels` = `["BE"]`
   - `fields.assignee` = `{ "accountId": "<1번에서 얻은 본인 id>" }`
   - `fields.customfield_10020` = `<활성 스프린트 id (숫자)>` — **활성 스프린트 자동 추가.** id 는 `searchJiraIssuesUsingJql` 로 `project = KB AND sprint in openSprints()` (maxResults 1, fields: `customfield_10020`) 를 조회해 `state:"active"` 인 항목의 `id` 를 쓴다. 활성 스프린트가 없으면(스프린트 사이 기간) 생략하고 보고에 명시한다.
   - `fields.parent` = `{ "key": "<2번에서 고른 에픽 키>" }` — "에픽 없음"이면 생략.
   - `fields.customfield_10016` = `<2번에서 확정한 SP (숫자)>`

5. **보고** — 생성된 키·URL(`https://simhani1.atlassian.net/browse/<KEY>`)과 함께 스프린트·에픽·SP 지정 결과를 사용자에게 전달한다.

## 본문 규약 (중요)

- **마크다운이 아니라 ADF** 로 넣어야 렌더된다. 마크다운 `- [ ]` 는 체크박스로 뜨지 않는다.
  - 개조식 → `bulletList` / `listItem` / `paragraph`
  - DoD 체크박스 → `taskList`(attrs.localId) / `taskItem`(attrs.localId, state `TODO`)
- **이슈 번호를 하이퍼링크로 걸지 않는다.** GitHub 이슈(#NN) 링크 금지. Jira 티켓은 그 자체로 태스크를 관리·이해하기 위한 것이므로, 외부로 점프하지 않아도 되도록 **Background 를 자체 완결형으로 친절히** 쓴다(현재 코드/구조 상태 → 문제 → 해결 시 이득을 직접 서술).
- 본문 구성은 보통 **Background(개조식) + DoD(체크박스)** 만.
- 한글 텍스트는 `\u` 이스케이프 대신 **한글 문자를 그대로** 넣어 오타를 피한다.

### ADF 예시 (Background 개조식 + DoD 체크박스)

```json
{
  "type": "doc",
  "version": 1,
  "content": [
    { "type": "heading", "attrs": { "level": 2 }, "content": [{ "type": "text", "text": "Background" }] },
    { "type": "bulletList", "content": [
      { "type": "listItem", "content": [
        { "type": "paragraph", "content": [{ "type": "text", "text": "현재 상태와 문제를 외부 링크 없이 이해되게 서술한다." }] }
      ] }
    ] },
    { "type": "heading", "attrs": { "level": 2 }, "content": [{ "type": "text", "text": "DoD" }] },
    { "type": "taskList", "attrs": { "localId": "dod" }, "content": [
      { "type": "taskItem", "attrs": { "localId": "dod-1", "state": "TODO" },
        "content": [{ "type": "text", "text": "완료 판정 기준 1" }] },
      { "type": "taskItem", "attrs": { "localId": "dod-2", "state": "TODO" },
        "content": [{ "type": "text", "text": "완료 판정 기준 2" }] }
    ] }
  ]
}
```

## 흔한 함정

- `atlassianUserInfo` 를 건너뛰고 특정인 accountId 를 하드코딩 → 팀원이 쓰면 남에게 할당됨. **항상 실행자 본인으로.**
- 마크다운 본문 → 체크박스/불릿 안 뜸. 반드시 `contentFormat:"adf"`.
- `editJiraIssue` 의 `description` 만 보내면 레이블·할당은 유지된다(부분 업데이트). 반대로 새로 지정할 땐 함께 보낸다.
- 스프린트 필드(`customfield_10020`)는 조회 시엔 배열로 오지만 **set 은 스프린트 id 숫자 하나**다. 배열로 보내면 실패.
- 스프린트 id 를 하드코딩하지 말 것 — 스프린트는 매주 바뀐다. **항상 `openSprints()` 로 재조회.**
- SP 5점 초과 금지 — 5점 추산이면 그대로 등록하지 말고 분할을 먼저 제안한다.
- 되읽기 응답의 `description` 은 마크다운으로 직렬화돼 `- [ ]` 로 보이지만, 저장된 본문은 taskList(실제 체크박스)다 — Jira UI 에서 확인.
