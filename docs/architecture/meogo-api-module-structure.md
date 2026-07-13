# Kbap 모듈 구조 정리

> **2026-07-13 갱신([ADR-0012](../adr/0012-dissolve-persistence-module-and-ports.md), KB-134)**: `:infra:persistence` 해체·리포지토리 port 폐기·모듈 리네임(`core/`→`domain/`, kernel→`:core`)이 반영됐다. 아래 컨텍스트별 개념 절의 "Repository 인터페이스" 표기는 도메인 서비스 창구로 읽는다.

> ⚠️ **구조 갱신(2026-06-29, ADR-0008)**: `kbap-api` 컨테이너는 해체되고 **모듈러 모놀리스**로 재편됐다. 현재 권위 있는 모듈/패키지 구조는 **[ADR-0008](../adr/0008-modular-monolith-shared-domain.md)** 와 루트 `CLAUDE.md`의 "모듈 구조"다. 현 경로: `:core:{kernel,food,member,scan,avoidance,research,review}` · `:application` · `:infra:persistence` · `:app:{api,batch}` · `:common`. 패키지는 `com.kbap.<layer>`(예: `com.kbap.domain.food`, `com.kbap.infra.persistence`, `com.kbap.app.api`). 아래 본문의 **DDD 계층 책임·의존 규칙은 유효**하나, 모듈 경로/이름 표기는 위 현행을 따른다(본문 일부 옛 표기는 역사적 맥락).
>
> 목적: 서버의 API, Application, Domain, Core, Infra 계층 구조와 책임을 정리한 팀 공유 기준 문서.
> 범위: 계층별 DDD 구조(API/Application/Domain/Core/Infra)에 집중한다. `:app:batch`(배치 앱)와 `:common`(공유 모듈)이 형제로 존재하며, batch 는 공유 도메인/영속을 직접 재사용한다(ADR-0008).

## 1. API 서버의 역할

`kbap-api`는 사용자의 모바일 앱 요청을 직접 받는 Spring Boot API 서버다.

MVP 기준 API 서버는 다음 책임을 가진다.

- 회원가입, 로그인, 인증/인가
- 사용자 프로필 및 식이 제한 정보 관리
- 메뉴판 스캔 결과 생성
- 클라이언트가 추출한 메뉴명 리스트 수신
- 음식 DB 캐시 조회
- 캐시 미스 음식에 대한 LLM API 병렬 호출
- LLM 응답 종합 및 음식 데이터 저장
- 사용자별 위험도 판정
- 음식 상세 조회
- 음식별 리뷰/평점 조회 및 작성은 제품 기획에 남아 있지만 현재 구현 범위에서는 보류
- 정적 다국어 UI 문구 제공

API 서버는 식당 탐색 서버가 아니다. 지도, 식당 상세, 식당별 리뷰는 MVP 범위에 포함하지 않는다.

## 2. DDD 적용 원칙

API 구조에 적용할 원칙은 다음이다.

1. Bounded Context를 명확한 코드 경계로 둔다.
2. 각 도메인은 자기 도메인 데이터와 규칙을 유일하게 관리한다.
3. Application Layer는 여러 도메인 기능을 조합해 유스케이스 흐름을 만든다.
4. 도메인 규칙을 Controller나 Application Service에 흩뿌리지 않는다.
5. JPA Entity, Mongo Document, Spring Data Repository는 Application/API 밖으로 노출하지 않는다.
6. Application은 JPA Entity나 Mongo Document가 아니라 Domain Entity, Command, DomainRepository를 사용한다.
7. Aggregate Root를 통해서만 Aggregate 내부 상태를 변경한다.

Kbap에 적용하면, `SAFE`, `CAUTION`, `DANGER`, `UNKNOWN` 판정 규칙은 컨트롤러나 응답 DTO에 있으면 안 된다. 위험도 판정은 `:domain:avoidance` 컨텍스트 정책으로 집중해야 한다.

Kbap 는 멀티앱(web `kbap-api` + 배치 `kbap-batch`)이며, 도메인 컨텍스트는 `kbap-api` 컨테이너 아래 Gradle subproject로 평탄하게 분리한다. active 컨텍스트는 `food`, `member`, `scan`, `avoidance`이며, `review`는 placeholder/deferred subproject다.

## 3. 최종 권장 구조

`kbap-api`는 빌드 파일 없는 **컨테이너**이고, 그 안에 실행/조율/도메인/코어/인프라 leaf 모듈이 평탄하게 들어간다. 배치 앱과 공유 모듈은 형제로 둔다.

- `:app:api`: web bootJar — controller, API DTO, Flyway 스키마 owner (도메인 모듈은 application 을 통해 런타임 전이 — ADR-0012 로 runtimeOnly 조립 소멸)
- `:application`: 유스케이스 조율(도메인 서비스 조합), transaction boundary
- `:domain:{food,member,scan,avoidance,research}`: active 도메인 컨텍스트 — 도메인 모델 + 도메인 서비스(public) + 영속(internal), `domain/` 컨테이너 직속
- `:domain:review`: deferred placeholder
- `:core`: 공통 타입·예외·유틸·외부 client seam·id 값 클래스 + 영속 공통(BaseEntity — compileOnly jakarta/hibernate)
- `:infra:llm`: LLM 외부 연동 어댑터(Spring AI) — 배치가 직접 의존
- `:app:batch`: 배치 bootJar — 도메인 서비스를 직접 조합해 잡 실행
- `:common`: 앱 간 공유 — 통합 이벤트·DTO·기술 공통(logback 조각·유틸·어노테이션), Spring-free

### 3.1 왜 도메인별 subproject로 두는가

API 서버가 제품의 중심이고, 배치(`kbap-batch`)는 그 application 유스케이스를 재사용하는 위성 앱이다. 도메인 컨텍스트는 Gradle subproject로 나누어 컴파일 의존 방향을 더 명확히 한다. 이 구조는 다음 장점이 있다.

- 도메인 간 직접 의존을 Gradle 의존성으로도 확인할 수 있다.
- 실행 서버는 하나라 운영 복잡도가 낮다.
- 추후 Batch/Worker가 필요해질 때 필요한 도메인 artifact만 재사용하기 쉽다.
- Application이 필요한 도메인 컨텍스트를 조합하는 구조를 유지할 수 있다.
- deferred 컨텍스트(`review`)를 active 도메인과 분리해 둘 수 있다.

## 4. 모듈 의존성 원칙

구체적인 Gradle 설정은 구현 단계에서 정한다. 이 문서에서는 의존성 방향만 고정한다.

도메인 간 조합은 `:application`의 Application Service에서 수행한다.

예를 들어 메뉴판 판정 유스케이스는 `scan`, `food`, `member`, `avoidance`를 모두 사용하지만, 이 네 컨텍스트가 서로의 내부 구현에 직접 의존하지 않는다.

- `:app:api`은 HTTP 요청/응답과 인증/인가에 집중한다.
- `:application`은 도메인 서비스와 외부 client seam 을 조합한다.
- 도메인 모듈은 도메인 규칙과 영속 코드를 캡슐화한다 — 영속은 `internal`, 공개 창구는 도메인 서비스 하나다(ADR-0012).
- `:core`는 공통 타입·공유 값 클래스·영속 공통을 제공한다.
- `:infra:llm`은 LLM 외부 시스템 연동을 담당한다.

도메인 컨텍스트는 별도 Gradle subproject이므로, 서로를 직접 의존성으로 선언하지 않는 한 컴파일 시점 참조가 생기지 않는다. 패키지 규칙, 코드 리뷰, ArchUnit 테스트는 이 경계를 보조로 강제한다.

## 5. :app:api

`:app:api`은 Spring Boot 실행 모듈이다.

### 5.1 책임

- HTTP API endpoint 제공
- Request/Response DTO 관리
- 인증/인가 적용
- Application Service 실행
- Transaction boundary 관리
- API 예외 응답 변환
- Swagger/OpenAPI 문서 제공

### 5.2 하지 말아야 할 것

- 음식 재료 규칙을 직접 판단하지 않는다.
- 알러지 위험도 규칙을 직접 계산하지 않는다.
- JPA Entity를 직접 사용하지 않는다.
- 특정 LLM 응답 구조를 Controller까지 노출하지 않는다.
- 도메인별 DB 테이블 구조를 API 응답 모델로 그대로 노출하지 않는다.

Application Service는 `:app:api`이 아니라 `:application`에 둔다. `:app:api`은 HTTP 요청/응답 변환과 인증/인가 적용에 집중한다.

## 6. :application

`:application`은 유스케이스 조율 모듈이다.

주요 책임:

- API Request에서 변환된 Command를 입력으로 받는다.
- 여러 도메인 컨텍스트를 조합한다.
- transaction boundary를 잡는다.
- 외부 API 호출이 필요한 경우 `:infra:external`의 client port를 사용한다.
- 도메인 규칙 자체는 직접 구현하지 않고 도메인 모듈의 policy/entity를 호출한다.

## 7. Bounded Context

MVP API 기준 active Bounded Context는 `kbap-api` 아래 5개 subproject로 둔다.

`review`는 제품 기획에는 남아 있지만 현재 도메인 설계·초기 구현 범위에서는 제외한다. repo에는 `:domain:review` subproject를 placeholder로 유지하되, 실제 리뷰 기능은 재개 시점에 다시 설계한다. 리뷰를 재개하더라도 `food` 컨텍스트에 섞지 않고 별도 컨텍스트로 유지한다.

컨텍스트 목록:

- `food`
- `member`
- `scan`
- `avoidance`
- `research` (미스 메뉴 조사·종합, 배치 전용)
- `review` (deferred placeholder)

## 8. food context

### 8.1 책임

- 음식 정보 관리 (검수된 카탈로그)
- 음식명, 별칭, 다국어 이름(한국어 원문 + 9개 언어) 관리
- 음식 설명 관리 (한국어 원문 + 9개 언어)
- 음식 대표 이미지 관리
- 음식별 주요 재료 관리
- 재료 정보 관리
- 재료별 알러지 유발 성분 매핑
- 종교/비건 주의 성분 관리
- 데이터 상태(검수)와 갱신 이력 관리, 만든 research를 ID로 참조 (LLM provenance 자체는 `research`가 소유 — ADR-0004)

### 8.2 주요 개념

- Food
- FoodName
- FoodAlias
- FoodDescription
- FoodIngredient
- FoodImage
- Ingredient
- IngredientName
- IngredientAlias
- AllergenMapping
- DietaryRestrictionMapping

`Food`와 `Ingredient`는 같은 bounded context에 있지만 같은 Aggregate가 아니다. `FoodIngredient`는 `ingredientId`를 참조한다. 음식 단위 포함 가능성 요약 점수는 관련 `FoodIngredient` 스코어 평균을 소수점 한 자리까지 반올림해 표시할 수 있지만, 사용자별 최종 위험도를 낮추는 산식으로 쓰지 않는다.

## 9. member context

### 9.1 책임

- 회원 상태 관리
- 사용자 국적 관리
- 사용자 언어 설정 관리
- 알러지 정보 관리
- 종교상 먹지 못하는 음식 관리
- 비건 여부 관리
- 매운맛 허용 정도 관리
- 관심 음식 관리

### 9.2 주요 개념

- Member
- MemberProfile
- MemberPreference
- DietaryProfile
- MemberAllergen
- ReligiousRestriction
- VeganPreference
- SpiceTolerance

`member` 컨텍스트는 음식 재료 상세 정보를 알 필요가 없다. 사용자가 어떤 제한 조건을 갖는지만 관리한다.

## 10. scan context

### 10.1 책임

- 메뉴판 스캔 요청 생성
- 클라이언트 OCR 결과 저장
- 메뉴명 위치 정보 저장
- 스캔 상태 관리
- 메뉴별 매칭 결과 관리
- 사용자에게 반환된 판정 결과 스냅샷 관리

### 10.2 중요한 전제

MVP에서는 OCR을 서버가 직접 수행하지 않는다. 클라이언트가 메뉴판 이미지에서 메뉴명 텍스트와 위치 정보를 추출한 뒤 서버로 전달한다.

따라서 `scan` 컨텍스트의 핵심 입력은 원본 이미지 자체가 아니라 `rawMenuName`, 메뉴 표시 순서, 클라이언트가 넘긴 보조 메타데이터에 가깝다.

### 10.3 주요 개념

- MenuScan
- RecognizedMenuItem
- FoodMapping
- ScanAssessmentSnapshot
- ClientScanMetadata

`scan` 컨텍스트는 음식의 재료를 직접 판단하지 않는다. 어떤 메뉴명이 들어왔고, 어떤 음식으로 매칭됐으며, 사용자에게 어떤 결과를 반환했는지만 관리한다.

## 11. avoidance context

### 11.1 책임

- 사용자 제한 조건과 음식 재료를 비교한다.
- 위험도를 `SAFE`, `CAUTION`, `DANGER`, `UNKNOWN`으로 결정한다.
- 위험 사유를 생성한다.
- 사장님에게 보여줄 한국어 질문 생성에 필요한 도메인 값을 만든다.

### 11.2 위험도 기준

- `SAFE`: 판정 가능한 음식이며 사용자 제한 조건과 충돌하는 주요 재료가 확인되지 않음
- `CAUTION`: 판정 가능한 음식이지만 제한 재료가 포함될 수 있거나, 재료 데이터가 부족해 안전하다고 말할 수 없음
- `DANGER`: 판정 가능한 음식이며 일반적으로 포함되는 재료가 사용자 제한 조건과 충돌함
- `UNKNOWN`: 음료, 세트/코스명, 상품명, 식별 불가 메뉴처럼 음식/재료 판정 대상이 명확하지 않아 평가할 수 없음

### 11.3 주요 개념

- RiskLevel
- AvoidanceResult
- AvoidanceReason
- IngredientRisk
- OwnerQuestion
- AvoidancePolicy

## 11.5 research context (배치 전용)

미스 메뉴를 조사해 신뢰할 음식 데이터로 만드는 파이프라인. 상세는 [`domains/research.md`](./domains/research.md), 결정은 [ADR-0004](../adr/0004-research-bounded-context.md).

### 11.5.1 책임

- 미스 메뉴 조사 대기열 관리(정규화 메뉴명 dedup)·상태
- LLM 3개 모델 병렬 호출 결과(제공자별 원본) 보관
- 여러 응답을 신뢰 결과로 종합(종합 정책 = **순수 도메인 서비스**)
- 음식명·재료·9개국어 번역 후보와 출처·검수 사유 생성

### 11.5.2 주요 개념

- ResearchRequest (조사 대기열 Aggregate Root)
- LlmResponse (제공자별 원본 응답)
- SynthesizedFoodProfile (종합 결과 → `food`가 영속)

> **배치 전용** — web 진입점(`:app:api`)은 이 컨텍스트 유스케이스를 노출하지 않는다. 조합 유스케이스는 `:application`의 배치 전용 패키지에 두고 `kbap-batch`가 트리거하며, ArchUnit으로 web 의존을 막는다(§17, 규칙 §도메인 간 의존 8).

## 12. review context (deferred)

현재 구현 범위에서는 리뷰 도메인을 구현하지 않는다. `:domain:review`는 placeholder이며, 아래 내용은 재개 시 참고할 보류 원칙이다.

### 12.1 추후 책임 후보

- 음식별 리뷰 작성
- 음식별 평점 계산
- 국적별 평점 계산
- 내 국적 리뷰 필터링
- 리뷰 수정/삭제
- 사용자 랭킹 표시용 리뷰 활동 정보 제공

### 12.2 주요 개념

- FoodReview
- ReviewRating
- ReviewContent
- ReviewerSnapshot
- FoodRatingSummary
- NationalityRatingSummary

리뷰는 식당이 아니라 음식에 귀속한다. 작성자 표시 정보, 국적 필터, 랭킹 반영, 번역 저장 여부는 재개 시점에 다시 결정한다.

## 13. :infra:external

`:infra:external`는 도메인의 영속성 구현을 담는 곳이 아니라, 도메인 밖 외부 시스템과 통신하는 기술 어댑터를 담는 모듈이다.

포함 대상:

- LLM API client
- 외부 storage client
- email/SMS/push client
- 외부 번역 API client
- 메시지큐 producer/consumer
- 이벤트 발행/구독 adapter
- 외부 webhook client
- 외부 인증/결제/분석 서비스 client

비포함 대상:

- JPA Entity
- Mongo Document
- Spring Data Repository
- DomainRepository 구현체

위 영속성 관련 구현은 각 도메인 모듈 내부의 `adapter` 또는 `infrastructure` 패키지에 둔다.

in-process 도메인 이벤트의 이름과 payload 계약은 `:core` 또는 도메인 모듈에 둔다(앱 간 브로커를 타는 통합 이벤트는 `common`). 반면 Kafka, RabbitMQ, SQS 같은 실제 메시지 브로커 연결, 직렬화, retry, dead letter queue 처리는 `:infra:external`에 둔다.

### 13.1 LLM client

LLM 외부 API 호출을 담당한다.

대상 후보:

- Gemini
- OpenAI
- Upstage

`:infra:external`의 LLM client는 외부 API별 응답을 내부 공통 응답 모델로 변환한다. 이 공통 응답 모델은 `:core` 또는 `:application`의 port 계약으로 둔다. LLM 결과를 최종 음식 데이터로 확정하는 정책은 `:application`의 Application Service 또는 별도 assembler에서 수행한다.

여러 LLM 응답은 내부 공통 응답 모델로 변환한 뒤 Application 계층에서 종합한다.

이 LLM 병렬 호출·종합은 **`research` 컨텍스트가 소유**하고 **`kbap-batch`가 하루 1회 트리거**한다. 스캔 응답 경로(`kbap-api`)는 LLM client를 호출하지 않는다. LLM client(IO)는 `:infra:external`, 병렬 호출 오케스트레이션은 `:application`, **종합 정책은 `research`의 순수 도메인 서비스**에 두고, `kbap-batch`가 `:infra:external`를 조립해 application 유스케이스를 호출한다([ADR-0003](../adr/0003-pretranslated-batch-menu-pipeline.md)·[ADR-0004](../adr/0004-research-bounded-context.md), §17·§11.5 참고).

### 13.2 Storage client

메뉴판 이미지가 저장되는 외부 스토리지 연동을 담당한다.

MVP에서 서버가 OCR을 직접 하지 않더라도, 사용자가 촬영한 메뉴판 이미지 보관이 필요하다면 이 모듈을 사용한다.

### 13.3 Message queue / event adapter

메시지큐와 이벤트 발행/구독 기술 구현을 담당한다.

포함 예시:

- scan 완료 이벤트 발행
- LLM 처리 실패 이벤트 발행
- 알림 요청 이벤트 발행
- 배치/워커로 넘길 작업 메시지 발행
- 외부 브로커 consumer 설정
- retry, backoff, dead letter queue 설정

주의할 점:

- `ScanCompletedEvent` 같은 이벤트 의미는 domain/application 언어로 정의한다.
- Kafka topic, RabbitMQ exchange, SQS queue 같은 기술 세부사항은 infra에 숨긴다.

### 13.4 OCR contract

서버가 OCR을 직접 수행하지 않더라도 클라이언트 OCR 결과 계약은 명확해야 한다.

이 모듈은 원문 텍스트, 화면 위치, 신뢰도, 표시 순서 같은 클라이언트 OCR 결과 계약을 다룬다.

## 14. 주요 Application 유스케이스

### 14.1 회원가입 및 초기 프로필 등록

회원가입과 초기 식이 제한 정보를 등록한다.

사용 컨텍스트는 `member`이다.

### 14.2 식이 제한 프로필 수정

사용자의 알러지, 종교, 비건, 매운맛 허용 정도를 수정한다.

사용 컨텍스트는 `member`이다.

### 14.3 메뉴판 스캔 분석

메뉴판 스캔 결과를 생성하는 핵심 유스케이스다.

사용 컨텍스트와 모듈:

- `scan`
- `food`
- `member`
- `avoidance`

처리 흐름은 사용자 프로필 조회, 스캔 생성, 메뉴명 저장, 음식 캐시 조회, 캐시 히트 음식의 위험도 판정, 결과 스냅샷 저장 순서로 본다. **캐시 미스 음식은 결과 없음으로 응답하고 미스 메뉴명을 `research`에 적재한다 — 이 경로는 LLM을 호출하지 않는다**([ADR-0003](../adr/0003-pretranslated-batch-menu-pipeline.md)). 미스 메뉴의 조사·LLM 종합은 §14.6 배치 유스케이스가 담당한다.

### 14.6 미스 메뉴 배치 처리 (research 조사·종합)

캐시 미스로 적재된 메뉴를 하루 1회 조사해 음식 데이터·다국어 번역을 만들어 캐시를 채운다. **조사·종합 로직은 `research` 컨텍스트가 소유**하고([ADR-0004](../adr/0004-research-bounded-context.md)), 조합 유스케이스(`ProcessPendingResearch`)는 `:application`의 **배치 전용 패키지**에 둔다. **`kbap-batch`는 이 유스케이스를 스케줄에 맞춰 호출만** 하고 `:infra:external`(LLM client)를 조립해 실행한다(비즈니스 로직을 Job에 두지 않는다 — 규칙 §도메인 간 의존 8).

사용 컨텍스트와 모듈:

- `research` (조사 대기열·LLM 응답·종합 정책)
- `food` (종합 결과 영속)
- `:infra:external`의 LLM client (병렬 호출 IO)

처리 흐름은 `research` 적재 미스 메뉴 조회(정규화·중복 제거), LLM 3개 모델 병렬 호출(application이 core port로, 재료 조사 + 9개 언어 번역), `research` 종합 정책(순수 도메인 서비스), `food` 음식 데이터 생성 또는 보강(9개 언어), 처리 완료 표시 순서로 본다. 처리 후 같은 메뉴는 §14.3 스캔에서 캐시 히트가 된다.

### 14.4 음식 상세 조회

음식 상세 정보를 조회한다.

사용 컨텍스트는 `food`, `avoidance`, `member`이다.

사용자별 위험도를 다시 계산해야 하므로 `member`와 `avoidance` 컨텍스트가 필요하다.

### 14.5 음식별 리뷰 조회 (deferred)

음식별 리뷰와 평점 조회는 제품 기획에 남아 있지만 현재 구현 범위에서는 보류한다.

현재 구현 범위에서는 제외한다. 재개 시 사용 컨텍스트는 `review`, `member`가 된다.

재개 시 내 국적 리뷰만 보기 기능은 `member` 컨텍스트의 국적 정보를 기준으로 필터링한다.

## 15. API 설계 원칙

구체적인 endpoint 경로, 요청/응답 DTO, JSON 구조는 구현 단계에서 정한다. 이 문서에서는 API가 지켜야 할 원칙만 정의한다.

- API는 도메인 모델과 영속성 모델을 그대로 노출하지 않는다.
- 메뉴판 스캔 API는 클라이언트가 추출한 메뉴명 목록을 입력으로 받는다는 전제를 유지한다.
- 음식 상세 API는 음식 원본 정보와 사용자별 위험도 판정 결과를 내부적으로 분리해서 다룬다.
- 리뷰 API는 현재 구현 범위에서 제외한다. 재개 시 식당이 아니라 음식에 귀속한다.
- 정적 다국어 문구 API는 음식 데이터 번역 정책과 분리한다.

## 16. 언어 정책

DB에 저장하는 음식 콘텐츠는 **한국어(`ko`) 원문 + 9개 대상 언어**로 사전 번역해 저장한다([ADR-0003](../adr/0003-pretranslated-batch-menu-pipeline.md)).

9개 대상 언어: 중국어 간체(`zh-Hans`) · 영어(`en`) · 일본어(`ja`) · 중국어 번체(`zh-Hant`) · 베트남어(`vi`) · 인도네시아어(`id`) · 태국어(`th`) · 러시아어(`ru`) · 스페인어(`es`).

음식 콘텐츠에 포함되는 것(각 항목을 9개 언어로 번역):

- 음식명
- 음식 설명
- 재료명
- 알러지 유발 성분
- 종교/비건 주의 성분

번역은 `research`(배치)가 LLM으로 생성하고 `food`가 저장한다. 스캔 응답은 사용자 설정 언어의 사전 번역본을 즉시 내려준다.

정적 UI 문구는 음식 데이터와 별개 트랙으로 사전 번역해 저장한다.

정적 UI 문구 예시:

- 위험합니다
- 주문 전 확인하세요
- 사장님께 이 화면을 보여주세요
- 제가 알러지가 있어서 먹으면 위험합니다

## 17. LLM 처리 위치

LLM 조사·종합은 **`research` 컨텍스트가 소유**하고 **하루 1회 배치로 트리거**된다([ADR-0003](../adr/0003-pretranslated-batch-menu-pipeline.md)·[ADR-0004](../adr/0004-research-bounded-context.md)). 스캔 API(`kbap-api`)는 캐시 조회 + 위험도 판정만 동기로 수행하고, 캐시 미스는 결과 없음으로 응답한다.

이유:

- 스캔 API 응답을 외부 LLM latency·실패·비용에서 분리한다(빠르고 예측 가능한 응답).
- 재료 조사 + 9개 언어 번역은 무거우므로 모아서 배치로 처리하는 편이 비용·운영상 단순하다.
- 신규 메뉴는 첫 스캔에서 결과를 못 보고 배치 후(최대 ~1일) 제공되는 트레이드오프를 감수한다(클라이언트 UX로 "준비 중" 안내).

책임 분리: **종합 정책은 `research`의 순수 도메인 서비스**(IO 없음 → 테스트 쉬움), **LLM 병렬 호출(IO)은 `application`이 core port로**, **`kbap-batch`는 그 application 유스케이스를 시간 맞춰 호출만** 한다. 추후 신선도 요구가 커지면 배치 주기를 좁히거나 온디맨드 worker를 추가할 수 있다(트리거만 추가, 로직 재사용).

## 18. 트랜잭션 기준

Aggregate는 트랜잭션 일관성의 경계다.

API 유스케이스 기준 트랜잭션은 다음처럼 나눈다.

### 18.1 회원 프로필 수정

회원 프로필 수정의 트랜잭션 경계는 Member 또는 DietaryProfile Aggregate로 본다.

### 18.2 메뉴판 스캔 생성

메뉴판 스캔 생성은 MenuScan 생성, 미스 메뉴명 적재, ScanAssessmentSnapshot 저장을 각각 명확한 트랜잭션 경계로 다룬다. **스캔 경로에는 LLM 외부 호출이 없다** — 캐시 히트 메뉴만 판정한다.

권장 흐름은 MenuScan 저장 → 캐시 조회 → 캐시 히트 메뉴 판정 + 미스 메뉴명 적재 → AssessmentSnapshot 저장 → completed(미스가 있으면 부분 완료) 전환이다.

### 18.3 미스 메뉴 배치 처리

`research` 미스 메뉴 처리(배치)는 Food 생성 또는 갱신(9개 언어 번역 포함)을 트랜잭션 경계로 다룬다. LLM 외부 호출은 DB 트랜잭션 안에서 길게 잡지 않는다 — LLM 병렬 호출·`research` 종합 이후 `food`가 Food/FoodIngredient를 저장한다. 메뉴 단위로 처리하며, 일부 실패는 다음 배치에서 재시도한다.

## 19. 도메인/영속성 캡슐화 규칙

각 도메인 모듈(`:core:{food,member,scan,avoidance,research,review}`)은 외부에 Domain Entity와 DomainRepository interface만 공개한다.

JPA Entity, Mongo Document, Spring Data Repository, DomainRepository 구현체는 각 도메인 모듈 내부에 둔다. 다만 외부 모듈이 import하지 못하도록 패키지 가시성, 모듈 API 설정, 코드 리뷰, ArchUnit 테스트로 막는다.

`:app:api`와 `:application`은 JPA Entity, Mongo Document, Spring Data Repository를 import하면 안 된다.

이 방식의 핵심은 “외부 기술을 도메인 모듈에 둔다”가 아니라 “외부 기술 구현을 도메인 컨텍스트 내부에 숨기고, 바깥에는 도메인 언어만 공개한다”이다.

## 20. 팀 합의가 필요한 부분

아래는 구현 전에 결정해야 하는 항목이다.

1. ~~API 서버가 LLM 병렬 호출을 동기 응답으로 기다릴지~~ → **결정됨**: 스캔은 캐시 미스에 LLM을 호출하지 않고 결과 없음으로 응답, LLM 처리는 `kbap-batch`가 하루 1회([ADR-0003](../adr/0003-pretranslated-batch-menu-pipeline.md)). 남은 항목: 미스 메뉴 적재 저장소·중복 제거, 배치 주기 조정 기준
2. LLM 3개 응답을 종합하는 정확한 규칙
3. 배치가 만든 음식 데이터(9개 언어 번역 포함)의 DB 저장 전 검수 여부
4. 음식 대표 이미지 저장 방식
5. 리뷰 재개 시점과 번역 결과 저장 여부
6. `UNKNOWN` 식별 불가 메뉴를 사용자에게 안내하는 문구와 후속 행동
7. API 인증 방식
8. 리뷰 재개 시 작성 가능 조건

## 21. 최종 요약

`kbap-api`는 단순한 controller 모음이 아니라, Kbap MVP의 핵심 도메인을 실행하는 API 애플리케이션이다.

권장 구조는 다음이다.

- `:app:api`: web bootJar, controller, API DTO, 조립
- `:application`: 유스케이스 조율, transaction boundary
- `:core:{food,member,scan,avoidance,research}`: active 도메인 컨텍스트 (`research`는 배치 전용 조사·종합)
- `:domain:review`: deferred placeholder
- `:core`: 공통 타입, 예외, 이벤트, 유틸
- `:infra:external`: 메시지큐, 외부 API, 이벤트 발행/구독 client
- `:app:batch`: 배치 bootJar (application 트리거) · `:common`: 앱 간 공유 계약

가장 중요한 원칙은 다음이다.

- Controller는 얇게 유지한다.
- Application Service는 유스케이스 흐름만 조율한다.
- 도메인 규칙은 각 도메인 모듈 내부 컨텍스트에 둔다.
- JPA Entity, Mongo Document, Spring Data Repository, 영속성 Adapter는 각 도메인 모듈 내부에 숨긴다.
- 도메인 간 직접 의존은 피하고 Application Service가 조합한다.
- LLM 병렬 호출·종합·9개국어 번역은 `kbap-batch`가 하루 1회 담당하고, 스캔 API는 캐시 미스를 결과 없음으로 응답한다([ADR-0003](../adr/0003-pretranslated-batch-menu-pipeline.md)). 추후 신선도 요구 시 배치 주기를 좁히거나 Worker 분리를 고려한 경계를 유지한다.
