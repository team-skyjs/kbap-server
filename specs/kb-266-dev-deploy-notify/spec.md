# Feature Specification: dev/prod 배포 GitHub Release 자동 발행 + 슬랙 API 변경 알림

**Feature Branch**: `kb-266-dev-deploy-notify`

**Created**: 2026-07-30 (v2 개정: 같은 날 — prod 확장·GitHub Releases 채택·슬랙 요약 방식 확정)

**Status**: Draft

**Input**: User description: "dev환경에 cicd로 배포가 될 때마다 슬랙으로 알림을 쏘고 싶어. 또한 릴리즈 노트 작성을 자동화해야 할 듯함. 어떤 API가 변경되었는지를 주로 포커싱해야 함. 프론트 개발자랑 소통 비용을 낮추기 위함임." (Jira KB-266) + 후속 확정: prod 포함, GitHub Releases 사용, Release 본문은 GitHub 자동 생성, 슬랙에는 OpenAPI 기계 비교(oasdiff) 요약(LLM 미사용).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 배포 결과 슬랙 알림 + API 변경 요약 (Priority: P1)

dev(develop 머지)·prod(main 머지) 배포가 끝나면 팀 슬랙 채널에 알림이 올라온다. 알림에는 환경·날짜·커밋, 그리고 **이번 배포로 달라진 API 목록**(추가/변경/삭제/Breaking — 직전 같은 환경 배포와의 기계 비교 결과)과 GitHub Release 링크가 담긴다. 프론트 개발자는 슬랙만 보고 대응 필요 여부를 판단한다.

**Why this priority**: 이 기능의 최종 목적(프론트·백엔드 소통 비용 절감)의 핵심 전달 경로.

**Independent Test**: API 를 변경하는 커밋을 머지해 dev 배포를 발생시키고, 슬랙 메시지에 해당 변경이 추가/변경/삭제 구분과 함께 나타나는지 확인한다.

**Acceptance Scenarios**:

1. **Given** 새 API 엔드포인트가 추가된 변경이 머지됨, **When** dev 배포가 성공하면, **Then** 슬랙 메시지에 해당 엔드포인트가 "추가"로 표시되고 Release 링크가 포함된다.
2. **Given** API 와 무관한 내부 변경만 머지됨, **When** dev 배포가 성공하면, **Then** 슬랙 메시지에 "API 변경 없음"이 명시된다.
3. **Given** develop 브랜치에 변경이 머지됨, **When** dev 배포가 실패하면, **Then** 슬랙에 실패 알림(워크플로 실행 링크 포함)이 올라온다.
4. **Given** main 에 변경이 머지됨, **When** prod 블루/그린 배포 시작이 확인되면, **Then** 슬랙 메시지가 "배포 시작" 문구로 발송된다(완료로 오인시키지 않음).

---

### User Story 2 - GitHub Release 자동 발행 (Priority: P2)

배포마다 GitHub Releases 에 릴리즈가 자동 발행된다. 태그는 `dev-YYYYMMDD-<short-sha>`(prerelease) / `prod-YYYYMMDD-<short-sha>`(정식)이고, 본문은 GitHub 자동 생성(직전 같은 환경 릴리즈 이후 머지된 PR 목록), 첨부로 그 시점의 OpenAPI 스냅샷(`openapi.json`)과 상세 비교 결과(`openapi-diff.md`)가 달린다.

**Why this priority**: 슬랙은 요약, Release 는 상세·이력의 단일 보관처. 슬랙 알림(P1)의 링크 대상이므로 함께 있어야 완결되지만, 슬랙 없이도 독립 가치가 있다.

**Independent Test**: 배포를 발생시키고 Releases 탭에서 태그·prerelease 구분·본문 PR 목록·첨부 2종을 확인한다. 같은 워크플로를 재실행해 릴리즈가 중복 생성되지 않음을 확인한다.

**Acceptance Scenarios**:

1. **Given** dev 배포 성공, **When** 릴리즈가 발행되면, **Then** 태그 `dev-YYYYMMDD-<short-sha>` 가 배포 커밋을 가리키고 prerelease 로 표시된다.
2. **Given** 같은 커밋에 대한 워크플로 재실행, **When** 릴리즈 발행 단계가 다시 돌면, **Then** 기존 릴리즈가 재사용되고 중복 생성되지 않는다.
3. **Given** 직전 prod 릴리즈 이후 여러 PR 이 누적됨, **When** prod 릴리즈가 발행되면, **Then** 본문에 그 PR 들이 모두 나열된다(dev 릴리즈 기준점과 섞이지 않음).

---

### User Story 3 - 배포 이력·API 변경 회고 조회 (Priority: P3)

"이 API 언제 바뀌었지?"를 확인하고 싶을 때, Releases 탭에서 환경별 릴리즈를 시간순으로 찾아 당시의 OpenAPI 스냅샷·diff 첨부를 확인할 수 있다.

**Why this priority**: P1·P2 산출물(릴리즈 + 첨부)이 자동으로 이력이 된다 — 별도 구현 없이 충족.

**Independent Test**: 배포 여러 번 후 과거 릴리즈의 첨부에서 해당 시점 API 외형을 확인한다.

**Acceptance Scenarios**:

1. **Given** 여러 번의 배포, **When** 과거 특정 릴리즈를 열면, **Then** 그 시점의 openapi.json·openapi-diff.md 를 내려받아 확인할 수 있다.

---

### Edge Cases

- 배포 실패: 릴리즈를 발행하지 않고 실패 알림만 발송한다.
- 알림·릴리즈 발행 실패: 이미 성공한 배포의 결과를 바꾸지 않는다(잡 분리). 실패 원인은 워크플로 요약에서 확인 가능해야 한다.
- 기존 이미지 재배포(`image_tag` 수동 입력): 코드가 그대로이므로 릴리즈를 만들지 않고 슬랙에 "재배포" 알림만 보낸다.
- 최초 도입(비교할 직전 릴리즈 없음): "초기 OpenAPI 스냅샷"으로 처리하고 diff 를 생략한다.
- prod 는 "배포 시작"까지만 성공으로 판정하는 기존 의미(KB-242)를 유지한다 — 릴리즈·슬랙 문구도 "배포 시작" 기준임을 명시하고, 이후 ECS 롤백이 일어나도 릴리즈는 남는다(감수).
- 같은 날 여러 배포: short SHA 로 태그가 구분된다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: dev·prod 배포 워크플로가 끝날 때마다(성공·실패) 지정 슬랙 채널로 알림을 발송해야 한다. prod 성공 문구는 "배포 시작"으로 표기한다.
- **FR-002**: 성공 알림에는 환경·날짜·커밋(short SHA)·API 변경 요약(추가/변경/삭제/Breaking 카운트와 엔드포인트 목록, 없으면 "API 변경 없음")·Release 링크가 포함되어야 한다. 상세 전문은 슬랙에 복사하지 않는다.
- **FR-003**: API 변경 요약은 배포 커밋의 OpenAPI 문서와 같은 환경 직전 릴리즈의 OpenAPI 문서를 **기계적으로 비교**해 산출한다(LLM·추측 금지). 비교에 쓰는 OpenAPI 문서는 배포 커밋의 코드와 정확히 일치해야 한다.
- **FR-004**: 배포 성공 시 GitHub Release 를 자동 발행한다 — 태그 `dev-YYYYMMDD-<short-sha>`(prerelease) / `prod-YYYYMMDD-<short-sha>`(정식), 태그는 배포 커밋을 가리키고, 본문은 GitHub 자동 생성 PR 목록, 첨부는 `openapi.json`·`openapi-diff.md`.
- **FR-005**: 릴리즈 발행은 멱등이어야 한다 — 워크플로 재실행 시 같은 태그의 릴리즈를 중복 생성하지 않는다.
- **FR-006**: dev 와 prod 는 각자 자기 환경의 직전 릴리즈만 기준점으로 삼는다(교차 금지).
- **FR-007**: 알림·릴리즈 기능의 실패가 배포 성공/실패 판정에 영향을 주지 않아야 하며, 기존 배포 로직(dev EC2+SSM, prod ECS blue/green "시작 확인")은 의미를 바꾸지 않는다.
- **FR-008**: 웹훅 URL 등 비밀 값은 GitHub Secrets 로 관리하고 로그·릴리즈 본문에 노출하지 않는다.

### Key Entities

- **릴리즈**: 환경별 배포 1건의 기록(GitHub Release). 태그(환경-날짜-SHA)·PR 목록 본문·OpenAPI 스냅샷/diff 첨부를 가진다.
- **OpenAPI 스냅샷**: 배포 커밋 시점 API 외형의 단일 산출물(`openapi.json`). 다음 배포의 비교 기준(baseline)이 된다.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 배포 워크플로 종료 후 5분 이내에 슬랙 알림이 도착한다.
- **SC-002**: API 외형을 변경한 배포의 100%에서 해당 변경이 슬랙 요약·diff 첨부에 누락 없이 나타난다(기계 비교로 보장).
- **SC-003**: 프론트 개발자가 슬랙 요약과 Release 만으로(백엔드 추가 문의 없이) 변경 API 와 대응 필요 여부를 판단할 수 있다.
- **SC-004**: 알림·릴리즈 기능 장애로 배포가 실패하거나 성공 판정이 바뀐 사례가 0건이다.
- **SC-005**: 워크플로 재실행으로 릴리즈가 중복 생성된 사례가 0건이다.

## Assumptions

- 슬랙 워크스페이스·채널이 있고 Incoming Webhook 을 발급받을 수 있다(신규 Secret `SLACK_RELEASE_WEBHOOK_URL`).
- 적용 대상은 dev·prod 의 **api 서버 배포**다. 배치 배포(deploy-batch-*)·staging 은 범위 밖.
- "API 변경"의 기준은 springdoc 이 생성하는 OpenAPI 문서의 차이다(엔드포인트 존재·요청/응답 스키마). 내부 구현만의 변경은 대상이 아니다.
- Release 본문의 PR 목록은 GitHub 자동 생성(`--generate-notes`) 수준이면 충분하다 — PR 템플릿 변경·본문 파싱은 하지 않는다(v2 단순화 결정).
- 릴리즈 이력의 보관·조회 수단은 GitHub Releases 다(docs/ 자동 커밋 없음).
- prod 릴리즈는 "배포 시작 확인" 시점에 발행되며, 이후 ECS 카나리/bake 단계의 롤백 가능성은 문구 명시로 감수한다.
