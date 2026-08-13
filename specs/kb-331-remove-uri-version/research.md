# Research: 전 API URI 버전 제거 + 버전 헤더 필수화

## R1. 헤더 필수화 메커니즘 — 기본값 폐지 + 폴백 리졸버

- **Decision**: `configureApiVersioning` 에서 `setDefaultVersion("1.0")` 을 제거하고, `useRequestHeader("X-API-Version")` 뒤에 **폴백 `ApiVersionResolver`** 를 추가한다: 요청 경로가 `/api/` 로 시작하지 않거나 정확히 `/api/app-version` 이면 `"1.0"` 반환, 그 외 null. null 이면 Spring 이 `MissingApiVersionException`(400) 을 던진다. `setVersionRequired(true)` 를 명시해 의도를 고정한다.
- **Rationale**: Spring 의 버저닝 필수화는 전역 설정이라 경로별 예외가 없다. 전역 필수로 두면 **관리자 콘솔 페이지(`/admin/**`, 브라우저는 헤더를 안 보냄)·swagger·actuator 의 MVC 매핑까지 깨진다** — 폴백 리졸버 하나로 "필수 범위 = `/api/**` − app-version" 을 표현하는 것이 최소 구현이다. API 존재는 spring-webmvc 7.0.8 javap 로 확인.
- **Alternatives considered**: ①전역 필수 + 콘솔·swagger 개별 대응 — 대응 지점이 흩어지고 누락 위험. ②필터에서 자체 검사 — 프레임워크 버저닝과 이중 관리. ③app-version 예외 포기 — 스펙 결정(복구 경로 관대 유지) 위반.

## R2. 에러 응답 — 전용 핸들러로 400 COMMON-002

- **Decision**: `GlobalExceptionHandler` 에 `MissingApiVersionException`·`InvalidApiVersionException` 핸들러를 추가해 400 + `BaseResponse.fail("COMMON-002", ...)` 로 응답한다. 신규 에러 코드는 만들지 않는다.
- **Rationale**: 현재 핸들러엔 `ResponseStatusException` 계열 처리가 없어 **`Exception` 폴백(500 COMMON-003)으로 새는 구조** — 헤더 누락이 서버 장애로 집계되면 안 된다. 클라이언트 분기 시나리오는 "요청을 고쳐라" 하나라 기존 `INVALID_REQUEST`(COMMON-002)로 충분하다.
- **Alternatives considered**: 신규 코드(COMMON-005 등) — 클라이언트가 헤더 누락을 따로 분기할 이유가 없어 기각.

## R3. 테스트 스윕 전략 — MockMvc 기본 헤더 주입

- **Decision**: 테스트 공통 설정에 `MockMvcBuilderCustomizer` 빈을 두어 `defaultRequest` 에 `X-API-Version: 1.0` 을 주입한다. 기존 33개 테스트 파일은 **경로 치환만** 하고 헤더 추가 스윕은 하지 않는다. 무헤더 거절·app-version 예외·구 경로 404 는 신규 `ApiVersionRequiredTest` 에서 customizer 를 우회한 raw MockMvc(`MockMvcBuilders.webAppContextSetup`)로 검증한다.
- **Rationale**: 수백 개 MockMvc 호출에 헤더를 일일이 붙이는 스윕은 diff 폭발 + 실수 표면. 기본 주입은 "새 클라이언트는 항상 헤더를 보낸다"는 스펙 가정과 일치하고, 필수화 동작 자체는 전용 테스트가 소유한다.
- **Alternatives considered**: 전 호출 헤더 추가 — 기계적으로 가능하지만 이득 없음. 기존 X-API-Version 명시 테스트(scan v2 등)는 그대로 두어도 defaultRequest 와 병합 시 명시 값이 이긴다(동작 확인은 구현 중).

## R4. 외부 소비자 — kbap-langchain 선행 배포 (critical)

- **Decision**: kbap-langchain `KbapClient` 에 `X-API-Version: 1.0` 헤더를 추가하는 PR 을 **kbap 배포 전에** 머지·배포한다(별도 repo 후속, 이 브랜치 범위 밖).
- **Rationale**: 코드 확인 결과 langchain 은 헤더를 보내지 않는다(계약 문서도 "생략 시 기본 1.0" 전제). 필수화가 먼저 배포되면 콘텐츠 적재 콜백 전량 400 → 재시도 소진 → DLQ (2026-08-13 DLQ 사건과 동일 패턴 — 재시도 1회 = LLM 그래프 전체 비용). 헤더 선행 추가는 현 서버(기본 1.0)와도 호환이라 언제든 안전.

## R5. 경로 스윕 범위

- **Decision**: `ApiPaths.V1` 삭제 + 참조 컨트롤러 12개(`home`·`bookmark`·`auth`·`member`·`scan(v1)`·`image×2`·`report`·`food`·`community`·`block`)의 매핑을 `ApiPaths.API` 기반으로 치환. WebConfig 의 JWT `addUrlPatterns` 13개 항목·게스트 예외 정규식 2개도 함께 치환. swagger `*Api` 문구의 `/api/v1` 서술 갱신.
- **Rationale**: `grep -rln 'ApiPaths.V1'` 전수 조사 결과. ArchUnit 에는 경로 규칙이 없음을 확인(치환 대상 아님). OpenApiSnapshotTest 는 스냅샷을 export 만 하므로(고정 비교 없음) 별도 재생성 불요.
- 관리자 콘솔은 서버렌더 폼(`/admin/**` 페이지 컨트롤러) 기반으로 `/api/admin` fetch 호출이 없어(리소스 grep 0건) 콘솔 측 변경은 없다.

## R6. 문서 갱신

- **Decision**: 구현 마지막 task 로 CLAUDE.md "API 엔드포인트 경로 규약" 절과 `docs/architecture/meogo-conventions.md` 의 레거시 V1 서술을 개정한다(V1 베이스 소멸·헤더 필수·app-version 예외).
- **Rationale**: 규약 문서가 코드와 어긋난 채 남으면 다음 세션이 구 규약으로 코드를 짠다.
