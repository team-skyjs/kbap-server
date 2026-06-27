# 0002. 공통 빌드 설정 — buildSrc 컨벤션 플러그인

- **상태**: Accepted
- **날짜**: 2026-06-26
- **관련**: [ADR-0001](./0001-multi-app-modular-layout.md), [gradle-made-easy](../guides/gradle-made-easy.md), [meogo-conventions](../architecture/meogo-conventions.md)

## Context

모듈이 11개(웹/배치 앱 + 도메인 5 + application/infra/core/common)이고 아키타입은 4종(전 모듈 공통 / Spring 라이브러리 / 부트 앱 / 도메인)이다. 같은 설정(Java 21 toolchain·Kotlin 엄격성·테스트·Spring BOM)을 모듈마다 복사하면 유지보수가 어렵다.

초기에는 **루트 `build.gradle.kts`의 `subprojects`/`configure(moduleProjects)` 블록**으로 공통 설정을 공유했다(단순함 우선). 그런데 [ADR-0001](./0001-multi-app-modular-layout.md)의 도메인 평탄화로 **동일한 도메인 모듈이 5개**가 되면서 dedup 이득이 커졌고, 모듈별로 "자기 아키타입"을 선언하는 구조가 더 명확해졌다.

## Decision

공통 빌드 설정을 **`buildSrc`의 컨벤션 플러그인**(미리 컴파일된 `meogo.*.gradle.kts`)으로 옮긴다. 각 모듈은 `plugins { id("meogo.<archetype>") }` 한 줄로 아키타입을 선언한다.

- `meogo.kotlin-common` — 전 leaf 공통(kotlin-jvm·java-library·Java 21 toolchain·엄격성·group/version·Kotest+JUnit). Spring-free 모듈(core/common)은 이것만.
- `meogo.spring-conventions` — Spring 라이브러리 공통(kotlin-common 합성 + kotlin-spring·dependency-management·Boot/AI BOM·reflect/jackson/starter-test).
- `meogo.spring-boot-application` — 부트 앱(spring-conventions + `org.springframework.boot`): `:meogo-api:api`, `:meogo-batch`.
- `meogo.domain-conventions` — 도메인 5종(spring-conventions + `api(:meogo-api:core)`·jpa/mongo 은닉·mysql/h2).

루트 `build.gradle.kts`는 집계 전용으로 비운다. 버전 카탈로그는 `buildSrc/settings.gradle.kts`에서 루트 `gradle/libs.versions.toml`을 `libs`로 가져오고, `buildSrc/build.gradle.kts`는 `libs.plugins.*`를 플러그인 마커 좌표로 변환해 서드파티 Gradle 플러그인을 classpath 에 올린다.

## Alternatives Considered

- **루트 `build.gradle.kts` + `subprojects`/`configure` (이전 방식)** — 한 파일에서 전체가 보여 직관적이나, Gradle 비권장 cross-project 설정이라 구성 캐시/프로젝트 격리에 불리하고 모듈이 늘면 루트가 비대해진다. 도메인 5종 dedup 도 약하다.
- **`build-logic` (included build)** — `buildSrc`와 개념은 같고 `includeBuild`로 연결. 여러 레포가 빌드 로직을 공유할 때 유리하나, 단일 레포에는 과하다.

## Consequences

- **좋음**: 도메인 5종이 한 줄로 dedup. 모듈이 자기 아키타입을 선언해 가독성↑. 컨벤션 플러그인이 진짜 Kotlin 코드라 IDE 타입세이프 지원. 루트 빌드 파일이 슬림.
- **트레이드오프**: `buildSrc` 변경 시 **전체 빌드 캐시가 무효화**돼 느려질 수 있다. 컨벤션 플러그인 안에서는 `libs.*` 타입세이프 접근자가 안 잡혀 `VersionCatalogsExtension.findLibrary/findVersion` 우회가 필요하다. Gradle 입문 난이도가 다소 오른다.
- **supersede**: 이전 루트 `subprojects`/`configure` 공통 설정 방식을 대체한다. (별도 ADR 없이 시작된 관행이라 supersede 대상 ADR 은 없음.)
