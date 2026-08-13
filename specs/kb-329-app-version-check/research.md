# Research: 앱 버전 정보 조회

## R1. 버전 값 저장 방식 — DB 단일 행

- **Decision**: 신규 테이블 `app_version` 에 단일 행으로 저장하고 관리자 API 로 갱신한다.
- **Rationale**: 사용자 결정(2026-08-13 plan 입력) — 관리자가 코드 배포 없이 컨트롤해야 한다. yml 프로퍼티는 값 변경마다 배포가 필요해 긴급 차단(치명 버그 버전 강제 업데이트)이 배포 파이프라인에 묶인다.
- **Alternatives considered**: yml 프로퍼티(스펙 초안·Jira DoD 의 원안 — 배포 필요로 기각), 외부 설정 서비스(과설계 — 값 4개에 인프라 추가 불필요).

## R2. 단일 행 모델 — update-in-place

- **Decision**: 행 하나를 갱신(dirty checking)한다. 이력 테이블·append 방식을 쓰지 않는다.
- **Rationale**: 현재 유효 값 하나만 의미 있고 이력 요구가 없다. 조회는 항상 "그 행"이라 latest 선택 로직도 불필요해진다.
- **Alternatives considered**: append + 최신 행 조회(공짜 이력이 생기지만 조회에 정렬·선택 로직이 붙고, 이력은 요구사항에 없음 — YAGNI).

## R3. 행 존재 보장 — Flyway 시드

- **Decision**: 테이블 생성 마이그레이션이 초기 행을 함께 INSERT 한다. 시드 값은 min=1.0.0·latest=1.0.1, 스토어 링크는 구현 시점에 실제 앱스토어 URL 이 확정돼 있으면 그 값, 아니면 NULL 시드 후 관리자가 채운다.
- **Rationale**: Flyway 는 앱이 트래픽을 받기 전에 실행되므로 "행 없음" 상태로 서비스되는 일이 없다(스펙 엣지 케이스 충족). 테스트도 운영과 같은 마이그레이션으로 스키마+시드를 얻는다.
- **Alternatives considered**: 애플리케이션 기동 시 upsert(마이그레이션과 책임 중복), 행 없으면 기본값 응답(정합 깨진 상태를 조용히 감춤 — 기각).
- 행 부재 시(운영 사고) `getAppVersion` 은 `BusinessException(INTERNAL_SERVER_ERROR)` — 신규 에러 코드를 만들지 않는다(클라이언트 분기 시나리오가 없는 서버 정합 오류).

## R4. 공개 경로 인증 — JWT 필터 미등록 = 무인증

- **Decision**: `GET /api/app-version` 은 `WebConfig` 의 JWT 필터 `addUrlPatterns` 에 등록하지 않는다(등록이 곧 보호인 opt-in 구조). admin 엔드포인트는 기존 `${ApiPaths.ADMIN}/*`(JWT 필터) + `AdminAuthorizationInterceptor`(`/api/admin/**`) 가 그대로 커버해 **WebConfig 변경이 없다**.
- **Rationale**: CLAUDE.md 의 "새 경로는 JWT 보호 경로에 반드시 등록" 함정은 보호가 필요한 경로 얘기다 — 이 API 는 로그인 전 호출이 요구사항이라 미등록이 정답. 통합 테스트가 무인증 200 을 고정한다.

## R5. 관리자 로직 분리 — Admin*Service

- **Decision**: 갱신 로직은 `com.kbap.api.admin.AdminAppVersionService` 에 두고 공개 조회 서비스(`api.appversion.AppVersionService`)와 분리한다. 컨트롤러는 리포지토리를 직접 호출하지 않는다.
- **Rationale**: 팀 확립 원칙 — 관리자 로직은 중복을 허용하고 admin 패키지 `Admin*Service` 로 분리, 공용 도메인 서비스를 오염시키지 않는다. 컨트롤러는 서비스 호출 + DTO 매핑만 담당한다.

## R6. 버전 형식 검증 — 요청 경계 소유

- **Decision**: semver(`major.minor.patch`) 형식 검증은 admin 갱신 요청 DTO 의 `@field:Pattern` 이 소유한다. 엔티티·서비스는 확정 값을 받는다. 위반은 기존 `INVALID_REQUEST`(COMMON-002) 처리 경로를 탄다.
- **Rationale**: 헌법 V 의 "외부 입력 검증은 요청 경계(요청 DTO)가 소유" 조항. 공개 조회는 입력이 없어 검증 대상이 없다.

## R7. 경로·버저닝

- **Decision**: 공개 조회는 `ApiPaths.API + "/app-version"`(싱글턴 리소스 단수형), admin 은 `ApiPaths.ADMIN + "/app-version"`. `X-API-Version` 분기 없이 기본 버전 핸들러로 둔다.
- **Rationale**: 신규 리소스는 `/api/<리소스>` 규약(레거시 `/api/v1` 에 추가 금지). 버전 정보는 컬렉션이 아닌 단일 자원이라 단수형이 정확하다.

## R8. ModuleBoundaryTest 허용 맵

- **Decision**: `allowedDomainDeps` 에 `"appversion" to emptySet()` 을 추가한다.
- **Rationale**: 신규 `common.domain.<ctx>` 는 허용 맵 등록이 없으면 ArchUnit 스펙이 실패한다. appversion 은 어떤 도메인도 참조하지 않는 독립 컨텍스트다.
