---
name: meogo-db-review
description: "meogo-server 데이터베이스 설계 검토 방법론 — JPA 엔티티·MongoDB 도큐먼트·Flyway SQL 을 함께(3소스 교차) 읽고 성능 저하(N+1·인덱스 누락·EAGER·타입/길이 불일치)와 요구사항 미흡(정규화·제약·관계·번역/소프트삭제 모델)을 심각도별로 검출한다. DB·스키마·엔티티·인덱스·쿼리 성능·마이그레이션 검토 시 반드시 사용."
---

# meogo 데이터베이스 설계 검토

JPA·MongoDB·Flyway 를 **하나의 관점**에서 본다. ORM 매핑과 실제 DDL 이 일치하는지, 성능 저하가 없는지, spec/data-model 요구를 충족하는지 검증한다. 코드는 고치지 않고 **발견 + 구체적 개선안(DDL/쿼리)**을 제시한다.

## 3소스 교차 점검 (핵심)

하나만 보면 놓친다. 항상 셋을 나란히 본다:

1. **JPA 엔티티** — `meogo-api/persistence/src/main/kotlin/com/meogo/api/persistence/<context>/*.kt`
2. **Flyway DDL** — `meogo-api/presentation/src/main/resources/db/migration/V*.sql`
3. **요구 명세** — `specs/<feature>/data-model.md`(필드·제약·관계), `spec.md`(요구사항)

MongoDB 사용 시 도큐먼트 클래스도 동일하게 명세와 대조한다. 이번 기능이 Mongo 미사용이면 그 사실을 명시하고 JPA/Flyway 에 집중한다.

> 주의: data-model.md 는 리팩터 후 stale 일 수 있다(예: US2 영속 위치/번역 모델 변경). 명세와 코드가 다르면 **어느 쪽이 의도인지** 리더에 확인하고, 단정 대신 불일치로 보고한다.

## 일치성 점검 (Blocker 후보)

- 엔티티 `@Column(name, length, nullable)` ↔ Flyway 컬럼명·`VARCHAR(N)`·NULL 제약이 **정확히 일치**하는가? (불일치 시 런타임 매핑 오류·데이터 잘림 위험.)
- FK 관계(`@ManyToOne`/조인 컬럼) ↔ Flyway `FOREIGN KEY`/조인 컬럼이 일치하는가?
- enum 저장 방식(문자열 길이)·BaseEntity 공통 컬럼(`id` BIGINT AUTO_INCREMENT, `status`, `created_at`, `updated_at`)이 DDL 에 반영됐는가?
- 타입 적합성: 금액·수량·percent·좌표 등에 적절한 타입/범위인가(예: inclusionPercent 0~100 을 담는 정수 타입)?

## 성능 체크리스트

- **N+1**: 애그리거트/컬렉션을 순회하며 로딩하는 경로가 있나? 도메인 매핑에 필요한 연관이 **fetch join** 으로 한 번에 로딩되나? (meogo 규약: 모든 연관 LAZY + fetch join.)
  - 영향 정량화: "음식 1건 조회 시 재료 수 N 만큼 추가 쿼리 → 상세 1건에 1+N 쿼리".
- **EAGER 누수**: `@ManyToOne`/`@OneToOne` 기본 EAGER 가 LAZY 로 덮였나? EAGER 가 남아 불필요 로딩하나?
- **인덱스**: 조회 키(예: `food.korean_name` exact match)·FK 컬럼·정렬 컬럼(`display_order`)·번역 조회 키(`(food_id, lang_code)`)에 인덱스/유니크 제약이 있나? Flyway 에 `CREATE INDEX`/`UNIQUE` 가 있나?
  - 영향: 인덱스 없는 조회 키는 풀스캔 → 데이터 증가 시 선형 저하.
- **유니크/중복**: 공유돼야 할 엔티티(예: 재료)가 음식마다 복제되지 않나(정규화)? 조인 테이블에 중복 row 방지 제약이 있나?
- **카티전 곱**: 여러 컬렉션을 동시에 fetch join 해 행이 폭증하지 않나? (필요 시 분리 조회 + 배치.)
- **트랜잭션·외부호출**: 외부 호출을 트랜잭션 안에 길게 잡지 않나(헌법: pending 저장→외부 호출→completed).

## 요구사항 적합성 체크리스트

- data-model.md 의 모든 필드·제약·관계가 엔티티+DDL 에 구현됐나(역추적)?
- 관계 카디널리티(1:N / N:M)가 도메인 의미와 맞나? 조인 테이블 모델이 적절한가?
- **번역/다국어 모델**: ko 원문은 본체에, 대상 언어는 번역 테이블에(`(owner_id, lang_code, name)`)? ko 는 번역 테이블에 들어가지 않나? 잘못된 lang_code row 가 폴백으로 오인되지 않나?
- **소프트삭제**: BaseEntity `@SQLRestriction("status='ACTIVE'")` 전제가 유지되나? 삭제가 row 제거가 아니라 status 전환인가?
- 제약(NOT NULL·UNIQUE·길이)이 도메인 규칙(필수값·중복금지)을 강제하나?

## 보고 형식

```
[Blocker] FoodNameTranslationJpaEntity ↔ V2__create_food_tables.sql — 컬럼 길이 불일치
  문제: 엔티티 @Column(length=100) vs DDL VARCHAR(50).
  영향: 50자 초과 번역 저장 시 잘림/오류. 다국어 데이터 정합성 깨짐.
  개선안: V 마이그레이션 컬럼을 VARCHAR(100) 으로 일치, 또는 엔티티를 50 으로 조정(명세 확인 후).

[Major] FoodRepositoryAdapter.findByKoreanName — 번역 조회 N+1 가능
  문제: 재료별 번역을 개별 조회.
  영향: 재료 N개 음식 상세 1건에 1+N 쿼리.
  개선안: findIngredientNameTranslations(ids, lang) 처럼 IN 절 배치 조회(이미 있으면 호출 경로 확인).

[Major] V2 — food.korean_name 인덱스 없음
  문제: 매칭 키 korean_name 에 인덱스/유니크 부재.
  영향: 음식 증가 시 상세 조회 풀스캔.
  개선안: CREATE UNIQUE INDEX ux_food_korean_name ON food(korean_name); (소프트삭제 고려해 정책 확인).
```

마지막에 **요약(Blocker n / Major n / Minor n)과 게이트 판정**을 명시한다.

## 영역 경계

- 앱 코드 로직·모듈 경계·테스트 스타일은 **code-reviewer** 영역. 발견하면 공유하되 중복 리뷰하지 않는다. 영속 변경이 없는 task 는 "DB 영향 없음"으로 간단히 보고하고 건너뛴다.
