# Research: 맵기 선호 미설정(스킵) 허용 — -1 센티널

Technical Context 에 NEEDS CLARIFICATION 없음 — 기존 스택·기존 값 흐름 위의 정책 변경이라 신규 기술 조사가 없다. 아래는 설계 결정 기록.

## D1. 미설정 표현: -1 센티널 vs nullable

- **Decision**: `Int` 유지 + -1 센티널 (`SPICINESS_UNSET = -1`).
- **Rationale**: 이슈(KB-158)가 -1 로 결정을 명시했고, 클라이언트 계약도 "-1 을 넘겨준다고 약속"(사용자 확인 2026-07-16). 저장(JSON)·조회 응답·요청이 전부 같은 값을 쓰므로 변환 지점이 없다. nullable 화하면 프로필 부분 수정에서 "null=유지" 규약과 "null=미설정" 이 충돌한다 — -1 센티널이 이 충돌을 원천 회피한다.
- **Alternatives considered**: `Int?`(null=미설정) — 부분 수정의 null=유지와 모호해져 3분법(프로필 사진의 빈 문자열 같은 별도 센티널)이 또 필요해짐. 거부.

## D2. 기본 상수 처리: DEFAULT 값 변경 vs 상수 교체

- **Decision**: `DEFAULT_SPICINESS_PREFERENCE`(5) 를 삭제하고 `SPICINESS_UNSET`(-1) 으로 교체.
- **Rationale**: "기본값"과 "미설정"이 같은 값이 됐다. `DEFAULT_SPICINESS_PREFERENCE = -1` 로 두면 이름이 거짓말(기본 맵기가 -1?)이 된다. 참조 지점은 `empty()`·`MemberProfileJson` 기본값·테스트뿐이라 리네임 비용이 없다.
- **Alternatives considered**: 두 상수 병존(`DEFAULT = UNSET`) — 같은 값의 상수 두 개는 다음 독자를 혼란시킴. 거부.

## D3. 검증 위치: 도메인 모델 단일 지점 유지

- **Decision**: `MemberProfile` 의 `init` require + `validatedSpiciness` 두 곳(동일 조건 `== SPICINESS_UNSET || in SPICINESS_RANGE`)만 수정. 컨트롤러/DTO 레벨 Bean Validation 추가하지 않음.
- **Rationale**: KB-147 이 만든 기존 구조 그대로 — 검증은 도메인 모델이 소유하고 MEMBER-009 로 거절한다. 온보딩·프로필 수정 두 진입점이 자동으로 같은 규칙을 탄다(호출자별 패치 불필요).
- **Alternatives considered**: `@Min/@Max` Bean Validation — -1 예외 표현이 안 되고(-1..10 으로 열면 -1~-2 사이 의미 왜곡), 검증 규칙이 두 계층으로 분산됨. 거부.

## D4. 레거시 profile JSON 에 spicinessPreference 키가 없는 회원

- **Decision**: 역직렬화 기본값이 상수를 따라 -1(미설정)로 바뀌는 것을 수용. 마이그레이션 없음.
- **Rationale**: 키 부재 = 사용자가 맵기를 고른 적 없음 = 미설정이 정책상 정확하다. 저장 데이터는 한 바이트도 바뀌지 않는다(FR-006).
- **DB 검토 결과 (구현 중 확인, database-expert)**: 키 부재 행은 **실존하지 않는다** — consolidation 마이그레이션(V2026.07.10.21.30.28)이 JSON 전환 시 전 행에 `COALESCE(spiciness_preference, 5)` 로 키를 백필했고, `MemberProfileJson.spicinessPreference` 는 non-null 이라 이후 직렬화도 항상 키를 쓴다. 따라서 기본값 변경은 **방어적 의미만** 가지며(향후 키 없는 JSON 유입 시 -1 해석), 기존 회원의 표시값 변화는 0건이다. 같은 이유로 "미설정=NULL" 신호가 이미 5 로 붕괴돼 소급 마이그레이션은 원리적으로 불가 — 소급 없음 판단이 재확인됐다.
- **Alternatives considered**: 키 부재 시 5 유지(하드코딩) — "고른 적 없는데 5" 라는 KB-158 이 없애려는 상태를 레거시에만 존속시킴. 거부. / 5→-1 소급 마이그레이션 — 사용자가 고른 5와 강제 기본 5를 구분 불가, 이슈 범위 밖. 거부.

## D5. 오류 메시지

- **Decision**: MEMBER-009 메시지를 "맵기 선호는 -1(미설정) 또는 0~10 사이여야 합니다" 로 갱신. 코드·HTTP 상태(400) 불변.
- **Rationale**: DoD 항목. 클라이언트는 code 로만 분기하므로(API 규약) 문구 변경은 자유.

## D6. 온보딩 필수화 (2026-07-17 계약 확정)

- **Decision**: 온보딩의 spicinessPreference 는 **필수 필드**(-1~10 반드시 전송) — `OnboardingRequest`·`MemberProfileInput`·`Member.completeOnboarding` 을 non-null `Int` 로. 미전송은 역직렬화 단계 400 COMMON-002.
- **Rationale**: 사용자 확정 — 온보딩 화면은 맵기 단계가 항상 존재(스킵=클라이언트가 -1 전송)하므로 서버가 필수로 강제하는 게 계약을 정직하게 표현한다. nickname 등 다른 온보딩 필수 필드와 동일 패턴(타입 강제)이라 별도 검증 코드가 없고, "생략 시 -1 저장" 초기안이 안고 있던 배포 전 가입 회원 저장값 5 잔존 회귀(Codex 발견, 진입점 치환으로 땜질)도 구조적으로 소멸한다.
- **Alternatives considered**: 생략→-1 저장(초기 Jira DoD 문구) — 프로필 수정 API 를 공용으로 쓰는 클라이언트 관점에서 "생략" 의미가 API 마다 달라지는 비대칭 + 진입점 치환 코드 필요. 사용자 결정으로 폐기.
