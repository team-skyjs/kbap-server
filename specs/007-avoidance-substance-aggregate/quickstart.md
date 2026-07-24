# Quickstart: 007 검증

## 무엇을 만드는가

data 를 이고 있던 `AvoidanceSubstance` enum 을 **식별자 enum `AvoidanceSubstanceCode` + 도메인 어그리게이트 `AvoidanceSubstance`** 로 분리한다. 표시명은 DB 단일 출처, port 는 어그리게이트 반환, JPA 분류는 String 저장.

## TDD 순서 (원칙 I)

Red → Green → Refactor 로 진행. 대략의 작업 순서:

1. **도메인 어그리게이트** — `AvoidanceSubstance` 단위 테스트 먼저: `displayName(KO)`=koreanName, `displayName(EN)`=translations[EN], 번역 없으면 ko 폴백, `belongsTo` 참/거짓, 불변식(categories 1~3). → 어그리게이트·`AvoidanceSubstanceCode`(무필드) 구현.
2. **JPA `toDomain` + 어댑터** — H2 슬라이스 테스트: 성분행+분류행 저장 → `byCategory`·`findByCodes`·`findByIngredientIds` 가 올바른 어그리게이트(번역·분류 포함) 반환, **KO 표시명이 저장된 korean_name 을 반영**(Finding ① 회귀: korean_name 을 바꾸면 KO 도 바뀐다), 조회 쿼리 수 상수(N+1 없음). → String 저장·배치 조립 구현.
3. **시드 정합 축소** — `AvoidanceCatalogSeedSyncTest` 를 V5 코드 집합 == `AvoidanceSubstanceCode.entries` 로 축소(koName/번역/멤버십 대조 제거).
4. **전이 유물 제거** — `AvoidanceCatalog`·`AvoidanceSubstanceTranslations` 및 `AvoidanceCatalogTest` 삭제(사용처 테스트뿐 확인됨).
5. **ArchUnit 회귀** — `AvoidanceSubstanceCode` 무데이터 + avoidance 엔티티가 도메인 enum `@Enumerated` 미사용.

## 검증 명령

```bash
./gradlew :core:avoidance:test          # 어그리게이트 단위
./gradlew :infra:persistence:test       # toDomain·어댑터 H2 슬라이스
./gradlew :app:api:test                 # 시드정합·ArchUnit
./gradlew build                         # 전체 회귀
```

## 완료 기준(spec Success Criteria 대응)

- **SC-001/002**: korean_name 변경 시 KO 표시명 100% 반영, enum 하드코딩 값 반환 0(Finding ① 회귀 테스트 Green).
- **SC-003**: 조회 쿼리 수가 성분 수와 무관(N+1 테스트/쿼리 카운트 Green).
- **SC-004**: 시드 코드 집합 불일치 시 `AvoidanceCatalogSeedSyncTest` 즉시 실패.
- **SC-005**: 기존 관측 동작(성분 목록·분류 소속·재료↔성분 매핑) 테스트 100% 통과 유지.
- 코드 내 성분별 koName/번역/분류 데이터 정의 0(전이 유물 제거로 달성).

## 후속(이 브랜치 밖)

- `/speckit-constitution` — 원칙 V "고정 reference taxonomy = 컴파일 enum 저장" 예외 문구를 enum 무데이터 전제로 조정(MINOR).
- #16 판정 로직이 어그리게이트/코드 enum 소비.
