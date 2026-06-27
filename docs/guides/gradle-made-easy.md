# Gradle 쉽게 이해하기 (meogo-api 기준)

> 목적: Gradle 문법과 구조가 낯선 사람도 이 프로젝트의 빌드 구성을 이해할 수 있게 돕는다.
> 예시는 모두 실제 `meogo-api` 코드에서 가져왔다.

---

## 0. 먼저 큰 그림

- **Gradle**은 빌드 도구다. 소스 컴파일, 라이브러리 다운로드, 테스트 실행, 실행 가능한 jar 생성을 자동화한다.
- 이 프로젝트는 `./gradlew`(Gradle **Wrapper**)로 Gradle을 실행한다. Wrapper를 쓰면 **로컬에 Gradle을 따로 설치하지 않아도 되고** 사용할 Gradle 버전도 `gradle/wrapper/gradle-wrapper.properties`에 고정된다(현재 9.5.1). 그래서 팀원 모두 같은 Gradle 버전으로 빌드한다.
- 설정 파일 이름은 `*.gradle.kts`다. 여기서 `.kts`는 **Kotlin 스크립트**를 뜻하며, 빌드 설정을 Kotlin 문법으로 작성한다.

---

## 1. 꼭 알아야 할 파일 3종

| 파일 | 비유 | 하는 일 |
|---|---|---|
| `settings.gradle.kts` | 책의 **목차** | 이 빌드에 속하는 모듈을 `include`로 등록하고 루트 이름을 정한다 |
| `build.gradle.kts` | 각 모듈의 **설정서** | 해당 모듈이 어떤 플러그인을 쓰고, 어떤 라이브러리에 의존하며, 어떻게 빌드되는지 정한다 |
| `gradle/libs.versions.toml` | **부품 목록표** | 라이브러리와 버전을 한곳에서 관리한다(버전 카탈로그) |

루트에 `build.gradle.kts`가 하나 있고, **각 모듈에도** `build.gradle.kts`가 하나씩 있다.

---

## 2. 프로젝트 = 모듈, 그리고 멀티모듈

Gradle에서 "프로젝트(project)"는 보통 우리가 말하는 **모듈** 하나에 해당한다.

- 루트 프로젝트(`meogo`) 아래에 여러 **subproject(모듈)** 가 있다 → **멀티모듈**.
- `settings.gradle.kts`의 `include(...)`로 모듈을 등록한다.

```kotlin
// settings.gradle.kts
rootProject.name = "meogo-server"

include(
    ":meogo-api:presentation",          // 콜론(:)은 계층을 뜻한다 → meogo-api 폴더 아래 api 모듈
    ":meogo-api:application",
    ":meogo-api:food",         // 도메인 컨텍스트 (meogo-api 직속으로 평탄화)
    ":meogo-batch",            // 배치 앱
    ":meogo-common",           // 공유 모듈
)
```

- 모듈 경로는 콜론으로 표기한다. 예: `:meogo-api:presentation`, `:meogo-api:food`, `:meogo-batch`.
- `meogo-api`는 빌드 파일 없는 **컨테이너 폴더**이고, 실제 모듈은 그 안의 leaf(`presentation`/`application`/`food`…)다.
- 한 모듈이 다른 모듈을 사용하려면 `project(...)`로 의존성을 추가한다.

```kotlin
// meogo-api/application/build.gradle.kts
dependencies {
    "implementation"(project(":meogo-api:food"))   // 다른 모듈에 의존
    "implementation"(project(":meogo-api:core"))
}
```

---

## 3. build.gradle.kts 안의 3대 블록: plugins / dependencies / tasks

### plugins { } — 기능 묶음 켜기
플러그인은 "이 모듈은 Kotlin 모듈이다", "이 모듈은 Spring Boot 실행 모듈이다" 같은 **기능 세트**를 켜는 스위치다.

```kotlin
plugins {
    id("meogo.domain-conventions")   // 우리가 만든 공통 설정(6장 참고)
}
```

### dependencies { } — 라이브러리와 모듈 의존성
"이 모듈에 어떤 라이브러리가 필요한지"를 적는다. 여기서는 **의존 종류(configuration)** 가 중요하다.

| 종류 | 의미 | 예시 |
|---|---|---|
| `implementation` | 이 모듈 내부에서만 쓰는 라이브러리. **이 모듈을 사용하는 다른 모듈의 컴파일 클래스패스에는 보이지 않는다**(런타임에는 전이됨) | `implementation(libs.spring.boot.starter.data.jpa)` |
| `api` | 의존성을 **바깥으로도 공개**한다. 이 모듈을 사용하는 모듈도 컴파일 시점에 해당 타입을 쓸 수 있다 | `api(project(":meogo-api:core"))` |
| `runtimeOnly` | 컴파일에는 필요 없고 **실행할 때만** 필요하다(주로 드라이버) | `runtimeOnly(libs.mysql.connector)` |
| `testImplementation` | **테스트 코드에서만** 쓰는 라이브러리 | `testImplementation(libs.kotest.assertions.core)` |
| `testRuntimeOnly` | 테스트 **실행 시점에만** 필요하다 | `testRuntimeOnly(libs.h2)` |

> **왜 implementation vs api 구분이 중요한가?**
> 이 프로젝트는 JPA(영속성 기술)를 각 도메인 모듈 안에 `implementation`으로 숨긴다. 그래서 `:meogo-api:presentation`와 `:meogo-api:application`은 JPA 타입을 **컴파일 시점에 import조차 할 수 없다**(아키텍처 규칙). 만약 `api`로 노출했다면 다른 계층에서도 JPA 타입을 직접 참조할 수 있었을 것이다.

### tasks — 실제로 실행되는 동작
`compileKotlin`, `test`, `bootJar`, `build` 같은 **작업 단위**다. `./gradlew build`를 실행하면 Gradle이 내부적으로 여러 task를 순서대로 실행한다(컴파일 → 테스트 → 패키징).

---

## 4. 버전 카탈로그 (`gradle/libs.versions.toml`)

라이브러리와 버전을 **한곳**에서 관리하는 표다. 모듈마다 버전을 따로 적지 않으니 버전이 엇갈릴 일이 줄어든다.

```toml
[versions]
spring-boot = "4.1.0"

[libraries]
spring-boot-starter-web = { module = "org.springframework.boot:spring-boot-starter-web" }
mysql-connector = { module = "com.mysql:mysql-connector-j" }

[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
```

빌드 파일에서는 하이픈(`-`)이 점(`.`)으로 바뀐 형태로 사용한다.

```kotlin
implementation(libs.spring.boot.starter.web)   // = spring-boot-starter-web
runtimeOnly(libs.mysql.connector)              // = mysql-connector
```

> 대부분의 Spring 스타터에는 **버전을 적지 않는다**. `spring-boot` BOM이 버전을 자동으로 맞춰주기 때문이다(아래 5·6장).

---

## 5. BOM이란?

BOM(Bill of Materials)은 **서로 호환되는 라이브러리 버전 묶음표**다. Spring Boot BOM을 가져오면 `spring-boot-starter-web`, `data-jpa`, `h2` 등의 버전을 일일이 적지 않아도 Spring Boot가 검증한 조합으로 자동 정렬된다. 카탈로그의 많은 항목에 버전이 비어 있는 이유다.

---

## 6. 핵심: 공통 설정은 어떻게 공유하나 (이 프로젝트가 택한 방식)

### 문제
모듈이 11개다(웹/배치 앱 + 도메인 5 + application/infra/core/common). 각 `build.gradle.kts`에 **똑같은 설정**(Java 21 toolchain, Kotlin 엄격성 옵션, 테스트 의존성, Spring BOM)을 11번 복사해 넣으면 유지보수가 어려워진다.

### 이 프로젝트의 선택: `buildSrc` 컨벤션 플러그인
공통 설정을 `buildSrc`의 **컨벤션 플러그인**(미리 컴파일된 `meogo.*.gradle.kts`)에 두고, 각 모듈은 **한 줄로 자기 아키타입을 선언**한다.

```kotlin
// 예: 도메인 5개(food/member/scan/assessment/review)는 전부 이 한 줄
plugins { id("meogo.domain-conventions") }
```

컨벤션 플러그인 4종(`buildSrc/src/main/kotlin/`):

| id | 적용 대상 | 담는 것 |
|---|---|---|
| `meogo.kotlin-common` | 전 leaf | kotlin-jvm·java-library·Java 21 toolchain·엄격성·공통 테스트 |
| `meogo.spring-conventions` | Spring 라이브러리(core/common 제외) | + kotlin-spring·BOM·reflect/jackson/test |
| `meogo.spring-boot-application` | bootJar 앱(`:meogo-api:presentation`, `:meogo-batch`) | + `org.springframework.boot` |
| `meogo.domain-conventions` | 도메인 5종 | + `api(:meogo-api:core)`·jpa/mongo·mysql/h2 |

플러그인끼리 **합성**된다: `domain-conventions` → `spring-conventions` → `kotlin-common`. 그래서 도메인 모듈은 한 줄로 위 세 층의 설정을 모두 받는다.

각 모듈 파일은 **자기만의 고유 의존성**만 적는다:

```kotlin
// 예: meogo-api/infra/build.gradle.kts — 이 모듈에만 필요한 것
plugins { id("meogo.spring-conventions") }
dependencies {
    "implementation"(project(":meogo-api:core"))
    "implementation"(libs.spring.ai.starter.openai)
}
```

> **왜 `"implementation"` 처럼 따옴표를 쓰나?** 모듈은 컨벤션 플러그인을 적용하지만, 그 경로로는 `implementation(...)` 타입 안전 단축표기가 모듈 스크립트에 항상 생성되지는 않는다. 그래서 설정 이름을 **문자열로 일관되게** 적는다(안전). 라이브러리 좌표 `libs.*`는 모듈 파일에서 정상 동작한다.
>
> **카탈로그 접근(컨벤션 플러그인 안):** 컨벤션 플러그인 안에서는 `libs.*` 타입세이프 접근자가 안 잡혀 `extensions.getByType<VersionCatalogsExtension>().named("libs")` 후 `findLibrary("...")`/`findVersion("...")`로 조회한다. buildSrc 가 루트 카탈로그를 보도록 `buildSrc/settings.gradle.kts`에서 `from(files("../gradle/libs.versions.toml"))`로 가져온다.

### 다른 방법은 없었나?
공통 설정을 공유하는 방법은 크게 3가지다.

1. **루트 `build.gradle.kts` + `subprojects`/`configure` 블록**
   - 장점: 한 파일에서 전체 공통 설정이 보여 이해·수정이 직관적. 작은~중간 규모에 실용적.
   - 단점: Gradle이 권장하지 않는 "cross-project 설정"이라 구성 캐시/프로젝트 격리에 불리. 모듈이 많아지면 루트 파일이 비대해진다.

2. **`build-logic` (included build)의 컨벤션 플러그인**
   - `buildSrc`와 개념은 같고 `includeBuild`로 연결하는 별도 빌드. 여러 레포가 빌드 로직을 공유할 때 유리.

3. **`buildSrc` 폴더의 컨벤션 플러그인 (현재 방식)**
   - 컨벤션 플러그인을 `buildSrc/`에 둬 자동으로 전 모듈에서 `id("meogo.*")`로 적용. 모듈별 아키타입 선언이 명확하고 IDE 타입세이프 지원을 받는다.
   - 단점: `buildSrc`를 한 글자만 고쳐도 **전체 빌드 캐시가 무효화**돼 느려질 수 있다.

> 초기엔 **1번(단순함)**으로 갔다가, 평탄화로 **동일한 도메인 모듈이 5개**가 되면서 dedup 이득이 커져 **3번(buildSrc)으로 전환**했다. 모듈이 늘수록 "모듈 파일은 한 줄, 공통은 플러그인" 구조가 깔끔하다.

---

## 7. 이 프로젝트 전체 그림

```
meogo-server  (rootProject.name = 폴더명)
├── settings.gradle.kts        ← 모듈 목차(include)
├── build.gradle.kts           ← 거의 빔(집계 전용 — 공통 설정은 buildSrc)
├── buildSrc/                  ← 컨벤션 플러그인(meogo.*) = 공통 빌드 설정
├── gradle/libs.versions.toml  ← 버전 카탈로그
├── meogo-api/                 ← 컨테이너(빌드 파일 없음)
│   ├── api/                   ← web 실행(bootJar)
│   ├── application/           ← 유스케이스 조율
│   ├── infra/                 ← 외부 client(LLM 등)
│   ├── core/                  ← 공통 타입·port (Spring-free)
│   └── food/ member/ scan/ assessment/ review/   ← 도메인 컨텍스트(평탄화)
├── meogo-batch/               ← 배치 실행(bootJar)
└── meogo-common/              ← 공유 모듈(통합 이벤트·DTO·기술 공통, Spring-free)
```

의존 방향은 한쪽으로만 흐른다.

```
[meogo-api 앱]
core ← 도메인(food/member/…) ← application ← presentation(bootJar)
  ↑                                 ↓
  └──────────── infra ──────────────┘   (presentation 이 runtimeOnly 로 조립)

meogo-batch  → :meogo-api:application 호출 (+ :meogo-api:infra 조립)
meogo-common ← meogo-api·meogo-batch 가 공유
```

---

## 8. 자주 쓰는 명령어

```bash
./gradlew build                       # 전체 컴파일 + 테스트 + 패키징
./gradlew :meogo-api:presentation:bootRun      # web 앱 실행 (특정 모듈의 task는 :모듈:task)
./gradlew :meogo-batch:bootRun        # 배치 앱 실행
./gradlew test                        # 전체 테스트
./gradlew :meogo-api:presentation:test         # 특정 모듈 테스트
./gradlew clean                       # 빌드 산출물 삭제
./gradlew projects                    # 모듈 목록 보기
./gradlew :meogo-api:presentation:dependencies --configuration runtimeClasspath  # 의존성 트리 보기
```

핵심은 `:모듈경로:task` 형태다. 예: `:meogo-api:food:test`, `:meogo-batch:test`.

---

## 9. 더 알아보기

- Gradle 공식 문서: https://docs.gradle.org/current/userguide/userguide.html
- 버전 카탈로그: https://docs.gradle.org/current/userguide/version_catalogs.html
- 컨벤션 플러그인(공유 빌드 로직): https://docs.gradle.org/current/userguide/sharing_build_logic_between_subprojects.html

---

> 이 프로젝트의 모듈 책임과 경계는 [`../architecture/meogo-api-module-structure.md`](../architecture/meogo-api-module-structure.md)를 참고한다.
