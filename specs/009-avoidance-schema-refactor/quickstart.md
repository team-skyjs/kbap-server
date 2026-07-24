# Quickstart: 기피 성분 스키마 리팩터 검증

**Feature**: 009-avoidance-schema-refactor | **Date**: 2026-07-03

이 문서는 구현 후(또는 TDD 중) 변경이 요구를 충족하는지 빠르게 확인하는 절차다.

## 사전

- worktree: `~/source_code/meogo/meogo-server-009`, 브랜치 `009-avoidance-schema-refactor`.
- 테스트는 H2(Flyway off, create-drop). prod 스키마는 V6(MySQL).

## 1. 전체 빌드·테스트 (회귀 게이트 — SC-003)

```bash
./gradlew build
```

기대: `BUILD SUCCESSFUL`. 카테고리 잔여 참조가 없어 컴파일·테스트 통과.

## 2. 카테고리 완전 제거 확인 (US2 / FR-001)

```bash
# 코드에서 사라졌는지
grep -rn "AvoidanceCategory\|byCategory\|belongsTo\|avoidance_substance_category" \
  core infra application app --include="*.kt" --include="*.sql"
```

기대: 매치 없음(또는 V5 시드 INSERT 만 — V5 는 불변, V6 에서 테이블 DROP). 도메인·영속·ArchUnit·테스트에 카테고리 참조 0건.

## 3. 번역 JSON 왕복 (US1 / FR-003·FR-004·FR-005)

`:infra:persistence` 의 `AvoidanceSubstanceRepositoryAdapterTest`(H2) 로 검증:

```bash
./gradlew :infra:persistence:test --tests "*AvoidanceSubstanceRepositoryAdapterTest*"
```

기대 시나리오(BehaviorSpec):
- given 여러 언어 번역을 가진 성분을 `translations` JSON 으로 저장 → when `findByCodes` 로 조회 → then 각 언어 `displayName(lang)` 이 저장값과 동일.
- given 특정 언어 번역이 없는 성분 → when 그 언어로 `displayName` → then `koreanName` 폴백.
- given 번역이 `{}` 인 성분 → then 모든 비-ko 조회가 `koreanName`.
- given 81종 전부 → when 조회 → then 전 성분 복원(카테고리 조인 제거로 누락 없음).

## 4. 마이그레이션 형태 확인 (R3)

```bash
cat app/api/src/main/resources/db/migration/V6__drop_avoidance_category_and_jsonify_translations.sql
```

기대: (1) `translations JSON` 추가, (2) `name_*` → JSON 백필, (3) `name_*` 9컬럼 DROP, (4) `avoidance_substance_category` DROP. V5 는 수정되지 않음(`git diff` 로 V5 무변경 확인).

## 5. API 무변경 (SC-004)

```bash
./gradlew :app:api:test
```

기대: food detail 등 기존 API 테스트가 응답 계약 변화 없이 통과(성분 이름은 요청 언어 유지, 카테고리는 원래 미노출).

## 완료 기준(Definition of Done)

- [ ] `./gradlew build` 통과
- [ ] `AvoidanceCategory`/`byCategory`/`avoidance_substance_category` 코드·스키마에서 제거(V5 시드 제외, V6 에서 DROP)
- [ ] `avoidance_substance` 가 `korean_name` + `translations`(JSON) 로 이름 보관, `name_*` 컬럼 없음
- [ ] JSON 왕복·ko 폴백·전 성분 복원 테스트 Green
- [ ] V5 무변경 + V6 forward 추가
- [ ] 기존 food/avoidance 관련 API·ArchUnit 테스트 Green
