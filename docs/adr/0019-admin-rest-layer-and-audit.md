# ADR-0019: 관리자 REST 층 신설 — 구 화면 병행, 전이 규칙 도메인 소유, 감사 이력

- 상태: 채택 (2026-08-25, KB-375)
- 관련: [ADR-0014](0014-public-repositories-persistence-ownership.md), [ADR-0017](0017-api-feature-packages.md), `specs/kb-375-admin-react-api/`

## 맥락

관리자 기능은 Thymeleaf 화면(`/admin/**`, 쿠키 로그인)과 REST(`/api/admin/**`, 관리자 JWT) 두 층으로 나뉘어 있었고, 화면 층이 대시보드·음식 편집·회원 조회를 독점했다. 운영자 인터뷰(2026-08-25)에서 확인된 문제:

- 파이프라인에 **개입**하는 정식 수단이 없어 상태 드롭다운이 백도어로 쓰였다 — 재료 무검증 저장으로 앱 음식 상세가 500 이 날 수 있고, 재료·이미지 없는 음식이 READY 로 노출될 수 있었다.
- 어떤 관리자 조작도 누가·언제·무엇을 바꿨는지 남지 않았다.
- 관리자 화면을 React 로 분리하려는데 SPA 가 토큰을 얻을 경로가 없었다(쿠키 로그인뿐).

## 결정

1. **REST 층을 정식 관리자 API 로 승격**하고 구 화면은 React 전환이 끝날 때까지 **그대로 병행**한다. 구 화면은 신 서비스(`AdminFoodCommandService`·검증기)를 공유하도록 최소 수정(버전 hidden·맵기 범위·상태 읽기 전용·승인/반려 폼)만 한다.
2. **관리자 자격을 회원 자격과 분리**한다 — 갱신 토큰에 `role` 클레임을 넣어 회원/관리자 refresh 를 교차 거부하고, 필터는 ADMIN 토큰에 `authAdminId` 속성·MDC `adminId` 만 심는다(`@AuthAdminId`). 관리자 TTL 은 `kbap.auth.admin.*`. 로그인 5회 실패 시 15분 잠금(Redis).
3. **상태 전이는 `Food` 도메인이 소유**한다 — `allowedTransitions()`·`transition()`(APPROVE/REJECT/RESUBMIT/UNPUBLISH), APPROVE 전제(재료 조사됨·이미지 있음). 수정 API 는 상태를 받지 않는다.
4. **콘텐츠 검증 규칙은 `FoodContentValidator` 하나**가 소유하고 랭체인 적재·REST 수정·구 화면 수정이 공유한다. READY/PENDING_REVIEW 는 완성 규칙(번역 9개·재료 필수), 그 외 상태는 부분 콘텐츠를 허용하되 재료 코드·비율 규칙은 항상 적용한다.
5. **모든 관리자 쓰기 조작은 `admin_audit_log` 에 명시 기록**한다(`AdminAuditRecorder`, `MANDATORY` 전파, 변경 필드만 before/after). AOP 를 쓰지 않는다.
6. 목록 조회는 **네이티브 프로젝션**으로 `@SQLRestriction` 을 우회(삭제/탈퇴 포함)하고 JSON 검색을 지원한다.
7. 정지 회원은 `MEMBER-012` 전용 오류 — `findOrSignUp` 이 상태 무관 조회 후 판정한다.

## 결과

- 사고 위험(앱 상세 500·부적합 READY 노출·백도어·고착 아웃박스 불가시)이 API 규칙으로 닫힌다.
- 관리자 조작 이력이 조회 가능해져 강제 업데이트 오설정 같은 사고의 롤백 근거가 생긴다.
- 구 화면 회귀 테스트 20개 + 신 REST 테스트가 모두 그린이어야 머지한다.

## 대안

- 관리자 전용 refresh 저장소 분리 — 포트·어댑터·조립 3조각 추가, 클레임 방식과 안전성 동일. 기각.
- Hibernate Envers — 전 엔티티 이력 + 관리자 외 변경이 섞임. 기각.
- 구 화면 완전 무수정 — 백도어 잔존·규칙 이중화. 기각.
