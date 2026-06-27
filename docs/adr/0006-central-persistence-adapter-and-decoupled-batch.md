# 0006. 중앙 영속 어댑터 모듈(`:meogo-api:persistence`) 채택 + `meogo-batch` 완전 디커플드

- **상태**: Accepted
- **날짜**: 2026-06-28
- **관련**: specs/001-menu-scan-mock, [ADR-0001](./0001-multi-app-modular-layout.md)(영속 캡슐화·batch↔application 결합 결정을 supersede), [ADR-0003](./0003-pretranslated-batch-menu-pipeline.md), [ADR-0004](./0004-research-bounded-context.md), [meogo-conventions](../architecture/meogo-conventions.md), 헌법 v1.1.0(원칙 IV)

## Context

ADR-0001은 두 가지를 결정했다.

- **영속 per-domain 캡슐화** — JPA Entity·Repository·adapter 를 각 도메인 모듈 내부에 숨기고, **중앙 `infra-persistence` 모듈은 두지 않는다**(중앙 모듈은 "여러 도메인에 의존하는 결합 + MVP 엔 과함"으로 *기각*).
- **batch 는 위성 앱** — `:meogo-api:application`을 의존(컴파일)하고 `:meogo-api:infra`를 `runtimeOnly`로 조립한다.

US1(`specs/001-menu-scan-mock`) 구현에서 scan 도메인이 실제 JPA(엔티티·리포지토리·adapter)를 갖게 되자 두 결정 모두 마찰을 드러냈다.

- **영속 공통 코드의 거처가 없다.** 모든 엔티티가 공유할 `BaseEntity`(@MappedSuperclass, id·소프트삭제 status·created/updatedAt)·`EntityStatus` 를 둘 곳이 없다 — `core`/`common` 은 **Spring-free** 라 JPA 금지, **도메인끼리는 서로 의존 금지**(헌법). 임시로 BaseEntity-only 공유 모듈을 뒀으나, 단일 클래스/enum 만을 위한 모듈이 어정쩡했다. 컬럼을 엔티티마다 인라인 중복하는 안도 시도했으나 공통 영속 베이스가 흩어졌다.
- **batch 가 api 에 결합된다.** `:meogo-api:application` 의존 탓에 경로·런타임상 api 에 묶이고, 도메인 JPA repo/entity 스캔을 위해 진입점에 `@AutoConfigurationPackage(["com.meogo"])` 우회까지 필요했다. batch 를 브로커 이벤트로만 소통하는 **진짜 디커플드 컨슈머**로 두고 싶다는 요구가 분명해졌다.

## Decision

**클린아키텍처 ports & adapters 로 영속을 한 모듈에 모으고, batch 를 meogo-api 에서 완전히 떼어낸다.**

1. **중앙 영속 어댑터 모듈 `:meogo-api:persistence` 채택** — ADR-0001의 "중앙 영속 모듈 미설치" 결정을 **뒤집는다**. 모든 JPA/ORM(엔티티·Spring Data Repository·`RepositoryAdapter`·`BaseEntity`·`EntityStatus`)을 이 모듈에 모은다. 패키지는 `com.meogo.api.persistence.*`(컨텍스트별 하위 — 예: `com.meogo.api.persistence.scan`). 이 모듈은 **각 도메인 모듈을 `implementation`으로 의존**해 도메인의 **리포지토리 port 를 구현**하고, 영속성 엔티티 관리 책임을 진다(의존은 inward: persistence → 도메인 → core).
2. **도메인 모듈은 순수(Spring/ORM-free)** — model + port(리포지토리 인터페이스)만 갖는다. `domain-conventions` = `meogo.kotlin-common` + `api(:meogo-api:core)`(JPA/Mongo 의존 제거).
3. **엔티티는 `BaseEntity`(@MappedSuperclass) 상속** — 단일 모듈에 모이므로 공통 베이스 공유가 깔끔하고 컬럼 중복이 없다. 소프트삭제는 `@SQLRestriction("status = 'ACTIVE'")` 상시 적용.
4. **조립 책임은 presentation** — web bootJar `:meogo-api:presentation`이 `:meogo-api:infra`(외부/LLM)·`:meogo-api:persistence`(JPA)를 `runtimeOnly`로 조립한다. `application`은 도메인 port·타입만 `implementation` 의존(컴파일 클래스패스에 JPA 비노출). Flyway **스키마 owner 는 그대로 presentation**(엔티티는 persistence, 마이그레이션은 web 리소스).
5. **`meogo-batch` 완전 디커플드** — ADR-0001의 "batch↔application 결합" 결정을 **뒤집는다**. batch 는 `:meogo-common`만 의존하고 `meogo-api` 내부 모듈(application·infra·persistence·도메인)에 **일절 의존하지 않는다**. meogo-api 와는 `meogo-common`의 **통합 이벤트(브로커)** 로만 소통한다. 진입점의 `@AutoConfigurationPackage` 는 제거(조립할 JPA 가 없음).

ADR-0001의 나머지 결정(멀티앱 레이아웃·`meogo-common`·이벤트 배치 위치)은 유지된다 — 본 ADR은 그 중 **영속 캡슐화 방식**과 **batch 결합**만 supersede 한다.

## Alternatives Considered

- **per-domain 영속 유지(ADR-0001 원안) + 컬럼 인라인 중복** — BaseEntity 상속 없이 각 엔티티가 공통 컬럼(id·status·시각·`@SQLRestriction`)을 직접 중복 선언. 모듈 구조는 단순하나 공통 영속 베이스가 흩어지고, `EntityStatus` 만 든 단일-enum 공유 모듈이 어정쩡했다. 논의 끝에 기각.
- **BaseEntity/EntityStatus 만 담는 공유 모듈** — 도메인엔 여전히 JPA 가 흩어지고, 단일 클래스/enum 을 위한 모듈이 군더더기. 기각.
- **batch 가 `:meogo-api:application` 직접 의존 유지(ADR-0001)** — api 에 결합되고 도메인 repo 스캔 우회(`@AutoConfigurationPackage`)가 필요. 디커플 요구와 충돌해 기각.

## Consequences

- **좋음**: 도메인이 100% 순수(Spring/ORM-free)라 테스트·재사용이 쉽고 헌법 IV(영속 캡슐화)의 정신을 강화한다. 영속 기술이 한 모듈에 모여 `BaseEntity`·소프트삭제 공통화가 깔끔(중복 없음). batch 가 api 에서 완전히 분리돼 독립 배포·운영이 가능하고 진입점 우회가 사라진다. ports & adapters 경계가 Gradle 의존으로 드러난다.
- **트레이드오프**: `:meogo-api:persistence`가 여러 도메인에 의존한다 — ADR-0001이 "결합"을 이유로 기각했던 바로 그 의존이다. 단 **단방향 inward** 라 도메인 자율성은 유지된다. 컨텍스트가 늘면 이 모듈이 비대해질 수 있어, 필요 시 컨텍스트별 영속 모듈로 분리하는 여지를 남긴다. batch 는 이제 application 을 **in-process 로 호출할 수 없으므로**, research 미스 메뉴 조사·종합 트리거(ADR-0003·0004)는 **통합 이벤트 기반**으로 구체화해야 한다.
- **후속/리스크**: ① 경계(도메인→persistence 역의존 금지, application 컴파일 클래스패스에 JPA 비노출)를 **ArchUnit**으로 강제. ② batch↔api **통합 이벤트 계약·브로커 벤더** 확정(미정) — 그전까지 batch 는 트리거 스켈레톤만. ③ 컨텍스트 증가 시 persistence 모듈 비대화를 모니터링.
