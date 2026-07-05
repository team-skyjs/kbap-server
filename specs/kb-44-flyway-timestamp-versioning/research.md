# Phase 0 Research: Flyway 버전 규칙 전환

출처: Flyway 공식 문서 `documentation/concepts/migrations`(flywaydb.org, gh-pages) + 프로젝트 현황 조사.

## R1. Flyway 버전 파일명 포맷

**사실(문서 인용)**: 버전 마이그레이션 파일명 = `Prefix` + `Version` + `Separator` + `Description` + `Suffix`.
- Prefix `V`(configurable), Separator `__`(두 언더스코어), Suffix `.sql`.
- Version 은 **점(`.`) 또는 언더스코어로 파트 구분**. 유효 예시: `1`, `001`, `5.2`, `1.2.3.4.5.6.7.8.9`, `205.68`, `20130115113556`, `2013.1.15.11.35.56`, `2013.01.15.11.35.56`.

**Decision**: 점 구분 timestamp `Vyyyy.MM.dd.HH.mm.ss__description.sql` 채택(예: `V2026.07.05.14.30.12__add_review_table.sql`). 각 파트 두 자리 zero-pad.

**Rationale**: 문서가 명시적으로 유효 예시(`2013.01.15.11.35.56`)로 제시한 포맷. 초 단위라 같은 시각 충돌 확률 극소. 점 구분이 연·월·일·시·분·초 경계를 시각적으로 드러내 가독성이 좋다.

**Alternatives considered**:
- 정수 유지(`V11`): 문서 기본 권장(*"a simple increasing integer should be all you need"*)이나 병렬 머지 시 번호 충돌 — KB-44 가 푸는 문제. 기각.
- 붙여쓴 timestamp(`V20260705143012`): 유효하나 가독성 낮음. 사용자 C안 선택으로 점 구분 채택.
- 분 단위(`...HHmm`): 같은 분 충돌 여지. 초 단위가 더 안전. 기각.

## R2. 정렬 규칙과 정수·timestamp 공존

**사실(문서 인용)**: *"Versioned migrations are applied in the order of their versions. Versions are sorted **numerically** as you would normally expect."*

**Decision**: 기존 정수(`V1`~`V10`)와 신규 점 구분 timestamp 를 **혼재**시킨다. 숫자 정렬상 `10 < 2026.07.05.14.30.12` 이므로 기존 이력이 항상 앞선다.

**Rationale**: 정수·점 구분 모두 "숫자 파트열"로 취급돼 자연 정렬된다. 기존 파일을 리네임할 필요가 없어 checksum/history 를 보존한다.

**Alternatives considered**: 기존 파일 일괄 timestamp 리네임 → checksum 파손·이력 붕괴로 배포 장애. 강력 기각(금지 사례로 문서화).

## R3. Out-of-order 적용

**사실(문서 인용)**: Out-of-order 상태 존재 — *"This migration succeeded but it was applied out of order. **Rerunning the entire migration history might produce different results!**"* 기본값 `out-of-order=false` 에서는 이미 적용된 최신보다 과거인 신규 버전이 validate 실패를 유발.

**Decision**: `spring.flyway.out-of-order=true` 를 베이스 `application.yml` 에 추가. 더불어 **"각 마이그레이션은 다른 미적용 마이그레이션의 실행 순서에 의존하지 않도록 독립적으로 작성"** 원칙을 컨벤션 문서에 명시.

**Rationale**: 생성 시각 기반 버전은 "먼저 만들고 늦게 머지"하면 필연적으로 과거 버전이 뒤늦게 적용된다(out-of-order). 설정으로 배포 차단을 풀되, 문서 경고("재실행 시 결과 상이 가능")는 마이그레이션이 순서-독립적일 때만 무해하므로 원칙을 한 쌍으로 강제한다. `installed_rank` 가 실제 실행 순서를 보존하므로 이력 추적은 정상.

**Alternatives considered**:
- out-of-order off 유지 + "머지 직전 timestamp 재부여" 규율: 수작업·실수 취약, spec 의 "생성 시각" 규칙과 상충. 기각.
- out-of-order off 유지(현행): 과거 timestamp 뒤늦은 머지가 배포를 조용히 막음. 기각.

## R4. 컨벤션 문서 위치

**Decision**: 이원화 —
1. **CLAUDE.md**(컨벤션 섹션, Flyway/DB 규약 인접): 짧은 강제 규칙 + 올바른 예시 + 금지 사례 3종 + 상세 문서 포인터.
2. **`docs/architecture/meogo-conventions.md`**: 포맷 근거(공식 문서)·정수↔timestamp 공존·out-of-order·순서-독립 원칙의 상세 설명.

**Rationale**: CLAUDE.md 는 매 세션 로드되는 런타임 개발 가이드라 "신규 마이그레이션 만들 때 반드시 지킬 규칙"의 최적 위치(이미 Flyway/DB 규약이 여기 있음). 헌법 Governance 는 상세 규범을 meogo-conventions.md 에 두라고 지정 → 근거·설명은 그쪽.

**Alternatives considered**: 별도 ADR 신설 → 규칙 1건에 과함. 단, plan 검토 시 ADR 로 승격 여부는 tasks 단계에서 선택 가능(경미 결정이라 기본은 문서 2곳).

## R5. 설정을 둘 프로필 파일

**Decision**: 베이스 `app/api/src/main/resources/application.yml` 의 `spring.flyway` 아래 `out-of-order: true`.

**Rationale**: out-of-order 는 환경 무관 동작이라 전 프로필(local/dev/staging/prod)이 상속해야 일관됨. 프로필별 파일에 흩으면 누락 위험. 테스트 override(`src/test/resources/application.yml`)는 flyway off 라 영향 없음.

## R6. 검증 방법(자동 테스트 부재 보완)

**Decision**: 로컬 docker MySQL(`meogo-mysql`) 실측 — quickstart.md 런북으로 (a) 신규 timestamp 정상 적용, (b) 과거 timestamp out-of-order 적용, (c) 기존 checksum validate 통과를 확인.

**Rationale**: 프로젝트 관례상 테스트는 H2 + flyway off 라 마이그레이션이 실행되지 않음(알려진 갭 — memory: flyway-migration-validation-gap). Flyway 실동작 검증은 로컬 MySQL 부팅이 유일한 현실적 수단.
