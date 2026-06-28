---
name: tdd-implementation
description: "meogo-server 에서 실패 테스트를 통과시키는 최소 구현(Green)과 리팩터링(Refactor) 절차. 멀티모듈 경계·클린아키텍처 의존 방향·영속 캡슐화·도메인 불변·응답(BaseResponse)/경로(/api/v) 규약·Kotlin 주석 금지를 다룬다. 구현·코드 작성·Green·리팩터·리뷰 피드백 반영 작업 시 반드시 사용."
---

# TDD 구현 (Green·Refactor 단계)

test-writer 가 고정한 실패 테스트를 통과시키되 meogo 의 모듈 경계·아키텍처 규약을 지킨다. Red → **Green**(최소 구현) → **Refactor**(테스트 유지하며 정리).

## 절차

1. **테스트 읽기** — 통과시켜야 할 then 들과 기대값을 파악한다. 이것이 명세다.
2. **최소 구현(Green)** — 테스트를 통과시키는 가장 단순한 코드를 올바른 모듈/패키지에 작성한다(YAGNI). 필요 시 Flyway SQL 추가.
3. **Green 확인** — 해당 모듈 테스트 실행 → 전부 통과 확인(증거 캡처).
4. **Refactor** — 중복 제거·이름 개선·구조 정리. 다시 테스트 실행해 여전히 Green 임을 확인.
5. **보고** — 변경 파일 목록 + 실행 명령 + Green 증거를 리더·리뷰어에게 전달.
6. **피드백 반영** — code-reviewer·database-expert 지적을 받으면 수정 후 재검증.

## 모듈 경계 (의존 방향 — 헌법 II·III·IV)

```
presentation → application → 도메인(scan/food/...) → core
                              ↑                         ↑
              persistence ────┘(도메인 port 구현)        │
              infra (core port 구현) ───────────────────┘
presentation 이 persistence·infra 를 runtimeOnly 로 조립(컴파일 의존 X)
```

- **도메인 모듈**(`com.meogo.api.<context>`): 순수 — Spring/ORM-free. model + repository **port 인터페이스**만. 도메인끼리 직접 의존 금지(조합은 application 에서만).
- **application**: 유스케이스·`Input`/`Result`(Command/Query 명칭 금지)·교체 가능한 seam(인터페이스). 외부/영속은 **port 인터페이스로만** 사용, infra/JPA 구현체 직접 의존 금지.
- **persistence**(`com.meogo.api.persistence.<context>`): 모든 JPA 엔티티·Spring Data Repository·`RepositoryAdapter`. 도메인 port 를 구현.
- **presentation**(`com.meogo.api.presentation`): 컨트롤러·API DTO·`BaseResponse`·예외 핸들러·Flyway. JPA Entity·도메인 엔티티를 import 하지 않는다(application 공개 타입만).

## 영속(JPA) 규약 (고정)

- 모든 엔티티는 `com.meogo.api.persistence.BaseEntity` 상속 — `id`·`status`(소프트삭제)·`createdAt`·`updatedAt` 제공. 자체 id·시각 두지 않는다.
- 모든 연관(`@OneToMany`/`@ManyToOne`/`@OneToOne`/`@ManyToMany`)은 **`FetchType.LAZY`**. 함께 로딩할 땐 **fetch join 쿼리**로 명시(EAGER 금지). 트랜잭션 밖 도메인 매핑 시 필요한 연관은 fetch join 으로 미리 초기화.
- 컬럼은 **MySQL 기준**: 문자열은 `@Column(length = N)` 명시. Flyway DDL 과 **길이·타입 일치**.
- 소프트삭제는 BaseEntity 의 `@SQLRestriction("status = 'ACTIVE'")` 가 전 엔티티에 적용 — 조회에 status 조건 달지 않는다. 삭제는 `delete()`(status=DELETED).
- JPA 애너테이션은 use-site 타깃 없이(`@Id`/`@Column`, `@field:` 불필요).
- 도메인↔엔티티 변환은 **엔티티 안**: `fun toDomain(): Domain` + `companion object { fun from(domain): Entity }`. `RepositoryAdapter` 는 `Entity.from(...)`·`entity.toDomain()` 만 호출. 별도 Mapper 클래스 금지.

## 도메인 불변 (고정)

- 도메인 객체는 **불변** — 모든 상태 `val`. 상태 변경 메서드는 변형 대신 **새 인스턴스 반환**.
- data class public `copy` 노출 금지 — **`private fun copy(...)`** 로 통제된 복제만 허용.

## 응답·경로 규약 (presentation, 고정)

- 모든 컨트롤러 반환 타입은 **`ResponseEntity<BaseResponse<T>>`**. 성공 `BaseResponse.ok(payload)`, 실패 `BaseResponse.fail(message)`. raw 도메인/DTO 직접 반환 금지.
- 모든 경로는 **`/api/v{n}`** 으로 시작. `com.meogo.api.presentation.common.ApiPaths`(`const val V1 = "/api/v1"`) 상수에 리소스 경로를 이어 붙인다. `/api/v1` 하드코딩 금지.

## Kotlin 주석 금지 (고정)

`.kt` 파일에 라인·블록·KDoc 주석 일절 금지(main·test 동일). 설명이 필요한 "왜"는 커밋 메시지·docs·ADR 에 남긴다. self-documenting 네이밍·구조로 표현한다. (빌드 스크립트·Flyway SQL·yml 주석은 예외로 허용.)

## Green 확인 명령

```bash
./gradlew :meogo-api:<module>:test --tests "<FQCN>"   # 대상만 빠르게
./gradlew :meogo-api:<module>:test                     # 모듈 전체
./gradlew build                                        # 전체(머지 전 최종)
```

DI/조립 문제(빈 누락, port 미주입)는 presentation 의 `runtimeOnly` 조립과 컴포넌트 스캔(`scanBasePackages=["com.meogo.api"]`)을 점검한다.

## 원칙

- 테스트가 요구하지 않는 기능을 미리 만들지 않는다(YAGNI). Green 에 필요한 만큼만.
- **테스트를 약화시켜 통과시키지 않는다.** 테스트가 틀렸다고 판단되면 test-writer 에게 근거와 함께 이의 제기(임의 수정 금지).
- 막히면 systematic-debugging — 구현→의존→DI 순으로 점검하고, 오래 헤매면 리더에 부분 진행 보고.
- 리뷰 피드백은 방어 말고 기술적으로 검토 후 반영하거나 근거 있게 반론(receiving-code-review).
