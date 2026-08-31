# Research: 관리자 음식 수정 안정성

스펙에 NEEDS CLARIFICATION 은 없다. 설계 선택지 2건을 결정으로 고정한다.

## R1. 편집 토글 구현 방식 — 서버 렌더 `edit` 쿼리 파라미터

**Decision**: `GET /admin/foods/list?page={p}&detail={id}` 는 읽기 전용 모달(모든 입력 `disabled`, 저장 버튼 미노출, '편집' 링크 노출)을 렌더하고, `&edit=true` 가 붙으면 편집 가능 모달(입력 활성, 저장 버튼, '취소' 링크 = edit 없는 동일 URL)을 렌더한다. 컨트롤러는 `editMode: Boolean` 을 모델에 넣고 템플릿이 `th:disabled`/`th:if` 로 분기한다.

**Rationale**:
- 기존 화면이 이미 쿼리 파라미터 구동 SSR 이다(`detail={id}` 로 모달 열림, `updated`/`error` 배너). 같은 패턴의 연장이라 새 개념이 없다.
- '취소 시 원값 복원'이 공짜다 — edit 없는 URL 로 돌아가면 서버가 DB 값으로 다시 렌더한다. 클라이언트 상태 복원 코드가 필요 없다.
- MockMvc 로 Red-first 테스트가 가능하다(렌더된 HTML 의 `disabled`·저장 버튼 유무 검증). JS 토글이면 서버 테스트로 검증할 수 없어 헌법 원칙 I 과 충돌한다.
- 저장(POST) 경계도 서버가 지킨다: 읽기 전용 화면에는 저장 폼 자체가 렌더되지 않는다.

**Alternatives considered**:
- **인라인 JS 토글**(disabled 속성을 JS 로 on/off): 왕복 없는 UX 이점은 있으나 현재 admin 화면은 JS 없는 순수 HTML 이고, 서버 테스트 불가·원값 복원 로직 별도 구현 필요. 기각.
- **별도 편집 페이지**(`/admin/foods/{id}/edit`): 모달 UX 를 버리게 되고 화면 2벌 유지. 기각.

## R2. contentStatus 자동 보정 — `transitionByContentState()` 저장 후 호출, sticky 의미론이 우선순위

**Decision**: `AdminFoodService.updateFood` 가 폼 값을 전부 반영한 뒤(관리자가 고른 `contentStatus` 포함) `food.transitionByContentState()` 를 호출한다. 최종 저장 상태 = 전이 메서드의 반환 상태.

**Rationale**:
- 스펙의 우선순위 규칙이 기존 메서드 의미론과 정확히 일치한다: 전이 메서드는 현재 상태가 PENDING_REVIEW·READY 면 즉시 반환(수동 검수 판단 보존 = 수동 지정 우선)하고, 그 외에는 텍스트 완성도·이미지 유무로 INCOMPLETE/PENDING_IMAGE/PENDING_REVIEW 를 재계산한다(검수 이전 = 완성도 우선).
- 완성도 판정 단일 출처 유지 — 배치 파이프라인과 관리자 저장이 같은 규칙을 쓴다. 새 판정 코드 0줄, 추가는 호출 1줄.
- 검증 실패(빈 이름·중복·JSON 오류)는 전이 호출 전에 early return 하므로 "거절된 저장에서 보정 없음" 이 자동 충족된다.

**Alternatives considered**:
- **관리자 선택값 무조건 우선**: 상태 불일치 휴먼 에러(이슈의 문제 정의)를 그대로 방치. 기각.
- **완성도 무조건 우선(READY 도 강등)**: 검수 단계는 사람 판단이 정본이라는 도메인 의미론 위반 + `transitionByContentState` 개조 필요(배치 경로까지 영향). 기각.
- **상태 select 를 편집 불가로 잠그고 전부 자동**: READY 승격 같은 수동 검수 행위 자체가 이 화면의 존재 이유(PENDING_REVIEW→READY 는 수동 전이) — 수동 지정 경로를 없앨 수 없다. 기각.
